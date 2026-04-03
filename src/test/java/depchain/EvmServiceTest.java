package depchain;

import depchain.blockchain.EvmService;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Besu EVM integration.
 *
 * Deploys the ISTCoin ERC-20 contract and verifies that:
 *  1. Deployment succeeds and a valid contract address is returned.
 *  2. totalSupply() returns 100,000,000 * 10^2 = 10,000,000,000.
 *  3. balanceOf(initialOwner) equals totalSupply (all tokens minted to deployer).
 *  4. transfer() moves tokens correctly between accounts.
 *  5. approve() rejects changing a non-zero allowance (frontrunning protection).
 *  6. increaseAllowance / decreaseAllowance work correctly.
 *  7. transferFrom is blocked when allowance is insufficient (frontrunning attack demo).
 */
class EvmServiceTest {

    private static final Address DEPLOYER =
            Address.fromHexString("0x1000000000000000000000000000000000000001");
    private static final Address ALICE =
            Address.fromHexString("0x2000000000000000000000000000000000000002");
    private static final Address BOB =
            Address.fromHexString("0x3000000000000000000000000000000000000003");

    // Standard ERC-20 function selectors
    private static final String SEL_TOTAL_SUPPLY  = "18160ddd"; // totalSupply()
    private static final String SEL_BALANCE_OF    = "70a08231"; // balanceOf(address)
    private static final String SEL_TRANSFER      = "a9059cbb"; // transfer(address,uint256)
    private static final String SEL_APPROVE       = "095ea7b3"; // approve(address,uint256)
    private static final String SEL_ALLOWANCE     = "dd62ed3e"; // allowance(address,address)
    private static final String SEL_INC_ALLOWANCE = "39509351"; // increaseAllowance(address,uint256)
    private static final String SEL_DEC_ALLOWANCE = "a457c2d7"; // decreaseAllowance(address,uint256)
    private static final String SEL_TRANSFER_FROM = "23b872dd"; // transferFrom(address,address,uint256)

    // ABI selector for Solidity's Error(string) revert reason
    private static final Bytes ERROR_SELECTOR = Bytes.fromHexString("08c379a0");

    private EvmService evm;
    private Address contractAddress;

    @BeforeEach
    void deploy() throws Exception {
        evm = new EvmService();
        evm.createAccount(DEPLOYER, Wei.of(new BigInteger("1000000000000000000")));
        evm.createAccount(ALICE,    Wei.of(new BigInteger("1000000000000000000")));
        evm.createAccount(BOB,      Wei.of(new BigInteger("1000000000000000000")));

        // Load bytecode compiled from ISTCoin.sol
        String bytecodeHex = loadResource("/contracts/ISTCoin.bin");

        // ABI-encode constructor argument: initialOwner = DEPLOYER (address = 32 bytes, left-padded)
        String constructorArg = "000000000000000000000000" + DEPLOYER.toUnprefixedHexString();
        Bytes initcode = Bytes.fromHexString(bytecodeHex + constructorArg);

        contractAddress = evm.deployContract(DEPLOYER, initcode, 5_000_000L);
        assertNotNull(contractAddress, "Deployment must return a non-null contract address");
    }

    @Test
    void totalSupplyIs10Billion() {
        Bytes result = evm.callContract(DEPLOYER, contractAddress,
                Bytes.fromHexString(SEL_TOTAL_SUPPLY), 100_000L);

        BigInteger supply = toBigInt(result);
        // 100,000,000 tokens × 10^2 decimals = 10,000,000,000
        assertEquals(BigInteger.valueOf(10_000_000_000L), supply,
                "totalSupply should be 10_000_000_000 (100M IST with 2 decimals)");
    }

    @Test
    void deployerHoldsEntireInitialSupply() {
        BigInteger balance = balanceOf(DEPLOYER);
        assertEquals(BigInteger.valueOf(10_000_000_000L), balance,
                "Deployer should hold the entire initial supply");
    }

    @Test
    void transferReducesSenderAndIncreasesRecipient() {
        long amount = 1_000_00L; // 1,000.00 IST

        Bytes callData = Bytes.fromHexString(SEL_TRANSFER + addrArg(ALICE) + uint256Arg(amount));
        Bytes result = evm.callContract(DEPLOYER, contractAddress, callData, 200_000L);

        assertTrue(isBoolTrue(result), "transfer() should return true");
        assertEquals(BigInteger.valueOf(10_000_000_000L - amount), balanceOf(DEPLOYER));
        assertEquals(BigInteger.valueOf(amount), balanceOf(ALICE));
    }

    @Test
    void approveRejectsChangingNonZeroAllowance() {
        // Step 1: set allowance 0 -> 100 IST (allowed)
        Bytes approve1 = Bytes.fromHexString(SEL_APPROVE + addrArg(ALICE) + uint256Arg(100_00L));
        assertTrue(isBoolTrue(evm.callContract(DEPLOYER, contractAddress, approve1, 200_000L)),
                "approve(0 -> 100) should succeed");

        // Step 2: change allowance 100 -> 50 directly via approve() (must REVERT)
        Bytes approve2 = Bytes.fromHexString(SEL_APPROVE + addrArg(ALICE) + uint256Arg(50_00L));
        Bytes result = evm.callContract(DEPLOYER, contractAddress, approve2, 200_000L);
        assertTrue(isRevert(result),
                "approve() on a non-zero allowance must revert (frontrunning protection)");

        // Allowance stays at 100 (revert rolled back the change)
        assertEquals(BigInteger.valueOf(100_00L), allowanceOf(DEPLOYER, ALICE),
                "Allowance must remain at 100 after a reverted approve");
    }

    @Test
    void increaseAndDecreaseAllowanceWork() {
        // increaseAllowance: 0 -> 100
        Bytes inc = Bytes.fromHexString(SEL_INC_ALLOWANCE + addrArg(BOB) + uint256Arg(100_00L));
        assertTrue(isBoolTrue(evm.callContract(DEPLOYER, contractAddress, inc, 200_000L)),
                "increaseAllowance should succeed");
        assertEquals(BigInteger.valueOf(100_00L), allowanceOf(DEPLOYER, BOB));

        // decreaseAllowance: 100 -> 60
        Bytes dec = Bytes.fromHexString(SEL_DEC_ALLOWANCE + addrArg(BOB) + uint256Arg(40_00L));
        assertTrue(isBoolTrue(evm.callContract(DEPLOYER, contractAddress, dec, 200_000L)),
                "decreaseAllowance should succeed");
        assertEquals(BigInteger.valueOf(60_00L), allowanceOf(DEPLOYER, BOB));
    }

    @Test
    void frontrunningAttackIsBlocked() {
        // DEPLOYER gives BOB 100 IST allowance
        evm.callContract(DEPLOYER, contractAddress,
                Bytes.fromHexString(SEL_INC_ALLOWANCE + addrArg(BOB) + uint256Arg(100_00L)),
                200_000L);

        // DEPLOYER reduces BOB's allowance to 50 using decreaseAllowance (safe path)
        evm.callContract(DEPLOYER, contractAddress,
                Bytes.fromHexString(SEL_DEC_ALLOWANCE + addrArg(BOB) + uint256Arg(50_00L)),
                200_000L);

        // BOB tries to transferFrom the original 100 IST — must fail (only 50 allowed now)
        Bytes attack = Bytes.fromHexString(
                SEL_TRANSFER_FROM + addrArg(DEPLOYER) + addrArg(BOB) + uint256Arg(100_00L));
        Bytes attackResult = evm.callContract(BOB, contractAddress, attack, 200_000L);
        assertTrue(isRevert(attackResult),
                "transferFrom above the current allowance must revert (attack blocked)");

        // BOB can still use the reduced allowance of 50 IST
        Bytes legitimate = Bytes.fromHexString(
                SEL_TRANSFER_FROM + addrArg(DEPLOYER) + addrArg(BOB) + uint256Arg(50_00L));
        assertTrue(isBoolTrue(evm.callContract(BOB, contractAddress, legitimate, 200_000L)),
                "transferFrom within the current allowance must succeed");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BigInteger balanceOf(Address account) {
        Bytes callData = Bytes.fromHexString(SEL_BALANCE_OF + addrArg(account));
        return toBigInt(evm.callContract(DEPLOYER, contractAddress, callData, 100_000L));
    }

    private BigInteger allowanceOf(Address owner, Address spender) {
        Bytes callData = Bytes.fromHexString(SEL_ALLOWANCE + addrArg(owner) + addrArg(spender));
        return toBigInt(evm.callContract(DEPLOYER, contractAddress, callData, 100_000L));
    }

    /** @return true if the 32-byte ABI-encoded bool result equals true (last byte == 1) */
    private static boolean isBoolTrue(Bytes result) {
        return result.size() == 32 && result.get(31) == (byte) 1;
    }

    /**
     * @return true if the call reverted.
     *         Revert with reason: starts with Error(string) selector 0x08c379a0.
     *         Exceptional halt: Bytes.EMPTY.
     */
    private static boolean isRevert(Bytes result) {
        if (result.isEmpty()) return true;
        if (result.size() >= 4 && result.slice(0, 4).equals(ERROR_SELECTOR)) return true;
        return false;
    }

    /** ABI-encode an Address as a 32-byte left-padded hex string (no 0x prefix). */
    private static String addrArg(Address address) {
        return "000000000000000000000000" + address.toUnprefixedHexString();
    }

    /** ABI-encode a long as a 32-byte big-endian hex string (no 0x prefix). */
    private static String uint256Arg(long value) {
        return String.format("%064x", value);
    }

    private static BigInteger toBigInt(Bytes result) {
        return result.isEmpty() ? BigInteger.ZERO : new BigInteger(1, result.toArray());
    }

    private static String loadResource(String path) throws Exception {
        try (InputStream is = EvmServiceTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
