package depchain;

import depchain.blockchain.EvmResult;
import depchain.blockchain.EvmService;
import depchain.blockchain.Transaction;
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
 * Tests for gas fee calculation and enforcement (Issue #4).
 *
 * Rules verified:
 *  1. fee = min(gasPrice × gasLimit, gasPrice × gasUsed)
 *  2. gasUsed is determined by the EVM; cannot be set by the user
 *  3. If gasUsed > gasLimit: abort, do NOT refund gas
 *  4. Fee is deducted from sender's DepCoin balance (not IST Coin)
 *  5. gasPrice and gasLimit must both be > 0 (enforced in Transaction)
 *  6. Transactions rejected when sender balance < maxFee + value
 */
class GasFeeTest {

    private static final Address ALICE =
            Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB =
            Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    // Base gas cost for plain transfers matches Ethereum's 21 000
    private static final long GAS_TRANSFER = 21_000L;

    private static final long GAS_PRICE = 10L;
    private static final Wei  INITIAL_BALANCE = Wei.of(1_000_000L);

    private EvmService evm;

    @BeforeEach
    void setUp() {
        evm = new EvmService();
        evm.createAccount(ALICE, INITIAL_BALANCE);
        evm.createAccount(BOB,   INITIAL_BALANCE);
    }

    // -------------------------------------------------------------------------
    // 1. Plain DepCoin transfer — fee calculation
    // -------------------------------------------------------------------------

    @Test
    void transferSucceeds_feeEqualsGasPriceTimesGasUsed() {
        long gasLimit = 50_000L; // well above base 21 000
        long value    = 1_000L;

        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(value),
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertTrue(result.success(), "Plain transfer must succeed");
        assertEquals(GAS_TRANSFER, result.gasUsed(),
                "gasUsed for a plain transfer must equal the base transfer cost");

        long expectedFee = GAS_PRICE * GAS_TRANSFER;
        assertEquals(Wei.of(expectedFee), result.fee(), "fee = gasPrice × gasUsed");

        // Sender loses value + fee
        Wei expectedSenderBalance = INITIAL_BALANCE.subtract(Wei.of(value + expectedFee));
        assertEquals(expectedSenderBalance, evm.getBalance(ALICE));

        // Recipient gains exactly value (fee goes to no one — just burned)
        assertEquals(INITIAL_BALANCE.add(Wei.of(value)), evm.getBalance(BOB));
    }

    @Test
    void transferOutOfGas_gasLimitBelowBaseCost_abortAndChargeFullLimit() {
        long gasLimit = 10_000L; // below base transfer cost of 21 000
        long value    = 500L;

        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(value),
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertFalse(result.success(), "Transfer must be aborted when gasLimit < base cost");
        assertEquals(gasLimit, result.gasUsed(),
                "gasUsed must equal gasLimit when out-of-gas (no refund)");

        // Fee = gasPrice × gasLimit (no refund per spec)
        Wei expectedFee = Wei.of(GAS_PRICE * gasLimit);
        assertEquals(expectedFee, result.fee());

        // Sender loses only the fee — value was NOT transferred
        assertEquals(INITIAL_BALANCE.subtract(expectedFee), evm.getBalance(ALICE));
        assertEquals(INITIAL_BALANCE, evm.getBalance(BOB), "Bob's balance must be unchanged");
    }

    @Test
    void transfer_gasLimitExactlyAtBaseCost_succeeds() {
        long gasLimit = GAS_TRANSFER; // exactly 21 000

        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(100L),
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertTrue(result.success());
        assertEquals(GAS_TRANSFER, result.gasUsed());
        assertEquals(Wei.of(GAS_PRICE * GAS_TRANSFER), result.fee());
    }

    // -------------------------------------------------------------------------
    // 2. Balance enforcement — insufficient funds
    // -------------------------------------------------------------------------

    @Test
    void insufficientBalance_transactionRejected_noFeeCharged() {
        // ALICE has INITIAL_BALANCE; try to send almost all of it as value
        // leaving nothing for the fee.
        long gasLimit = 50_000L;
        Wei  value    = INITIAL_BALANCE; // would leave nothing for fee

        Transaction tx = Transaction.create(ALICE, BOB, value,
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertFalse(result.success(), "Must be rejected: balance < maxFee + value");
        assertEquals(0L, result.gasUsed(), "No gas consumed when pre-check fails");
        assertEquals(Wei.ZERO, result.fee(), "No fee charged on rejection");

        // Balances unchanged
        assertEquals(INITIAL_BALANCE, evm.getBalance(ALICE));
        assertEquals(INITIAL_BALANCE, evm.getBalance(BOB));
    }

    @Test
    void insufficientBalance_exactlyAtMaxFee_butNoValueCovered() {
        // maxFee = gasPrice * gasLimit; if balance == maxFee, adding any value fails
        long gasLimit = 50_000L;
        Wei  exactMaxFee = Wei.of(GAS_PRICE * gasLimit);

        // Create a sender with exactly maxFee balance
        Address poorSender = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");
        evm.createAccount(poorSender, exactMaxFee);

        // Trying to send 1 wei of value should fail (balance < maxFee + 1)
        Transaction tx = Transaction.create(poorSender, BOB, Wei.of(1L),
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertFalse(result.success());
        assertEquals(Wei.ZERO, result.fee());
        // Balance unchanged
        assertEquals(exactMaxFee, evm.getBalance(poorSender));
    }

    @Test
    void zeroValueTransfer_exactBalanceForFee_succeeds() {
        long gasLimit = GAS_TRANSFER;
        Wei  exactFee = Wei.of(GAS_PRICE * GAS_TRANSFER);

        Address sender = Address.fromHexString("0xdddddddddddddddddddddddddddddddddddddddd");
        evm.createAccount(sender, exactFee); // balance just enough for the fee

        Transaction tx = Transaction.create(sender, BOB, Wei.ZERO,
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        EvmResult result = evm.executeTransaction(tx);

        assertTrue(result.success());
        assertEquals(exactFee, result.fee());
        assertEquals(Wei.ZERO, evm.getBalance(sender)); // fully consumed by fee
    }

    // -------------------------------------------------------------------------
    // 3. Nonce management — incremented even on failure
    // -------------------------------------------------------------------------

    @Test
    void nonceIncrementedEvenOnOutOfGas() {
        long gasLimit = 1L; // guaranteed OOG

        Transaction tx = Transaction.create(ALICE, BOB, Wei.ZERO,
                Bytes.EMPTY, GAS_PRICE, gasLimit, 0);

        evm.executeTransaction(tx);

        assertEquals(1L, evm.getNonce(ALICE),
                "Nonce must increment even when the transaction is aborted");
    }

    @Test
    void nonceIncrementedOnSuccess() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L),
                Bytes.EMPTY, GAS_PRICE, GAS_TRANSFER, 0);

        evm.executeTransaction(tx);

        assertEquals(1L, evm.getNonce(ALICE));
    }

    // -------------------------------------------------------------------------
    // 4. Contract call — gas fee enforced
    // -------------------------------------------------------------------------

    @Test
    void contractCall_feeDeductedFromDeployerDepCoin_notFromIstBalance() throws Exception {
        // Deploy ISTCoin and verify that the gas fee is deducted from the DepCoin
        // balance (SimpleWorld balance), not from the ERC-20 IST token balance.
        Address deployer = Address.fromHexString("0x1000000000000000000000000000000000000001");
        Wei deployerStart = Wei.of(new BigInteger("1000000000000000000"));
        evm.createAccount(deployer, deployerStart);

        String bytecodeHex = loadResource("/contracts/ISTCoin.bin");
        String constructorArg = "000000000000000000000000" + deployer.toUnprefixedHexString();
        Bytes initcode = Bytes.fromHexString(bytecodeHex + constructorArg);

        long deployGasLimit = 5_000_000L;
        Transaction deployTx = Transaction.create(deployer, null, Wei.ZERO,
                initcode, GAS_PRICE, deployGasLimit, 0);

        EvmResult deployResult = evm.executeTransaction(deployTx);

        assertTrue(deployResult.success(), "ISTCoin deployment must succeed");
        assertTrue(deployResult.deployedAddress().isPresent(), "Deployed address must be present");
        assertTrue(deployResult.gasUsed() > 0L, "Deployment must consume gas");
        assertTrue(deployResult.gasUsed() <= deployGasLimit,
                "gasUsed must not exceed gasLimit");

        // Fee = gasPrice × gasUsed (actual, not capped at gasLimit for successful execution)
        Wei expectedFee = Wei.of(GAS_PRICE * deployResult.gasUsed());
        assertEquals(expectedFee, deployResult.fee());

        // Deployer's DepCoin balance reduced by exactly the fee
        Wei deployerAfter = evm.getBalance(deployer);
        assertEquals(deployerStart.subtract(expectedFee), deployerAfter,
                "DepCoin balance must decrease by exactly the gas fee");

        // ------------------------------------------------------------------
        // Now call totalSupply() and verify a second fee is charged.
        Address contractAddress = deployResult.deployedAddress().get();
        Wei balanceBeforeCall = evm.getBalance(deployer);
        long callGasLimit = 100_000L;

        Bytes callData = Bytes.fromHexString("18160ddd"); // totalSupply()
        Transaction callTx = Transaction.create(deployer, contractAddress, Wei.ZERO,
                callData, GAS_PRICE, callGasLimit, evm.getNonce(deployer));

        EvmResult callResult = evm.executeTransaction(callTx);

        assertTrue(callResult.success(), "totalSupply() call must succeed");
        assertTrue(callResult.gasUsed() > 0L);
        assertTrue(callResult.gasUsed() <= callGasLimit);

        Wei callFee = Wei.of(GAS_PRICE * callResult.gasUsed());
        assertEquals(callFee, callResult.fee());
        assertEquals(balanceBeforeCall.subtract(callFee), evm.getBalance(deployer),
                "DepCoin balance after call must decrease by call fee only");
    }

    @Test
    void contractCall_outOfGas_abortAndChargeFullLimit() throws Exception {
        Address deployer = Address.fromHexString("0x1000000000000000000000000000000000000001");
        evm.createAccount(deployer, Wei.of(new BigInteger("1000000000000000000")));

        String bytecodeHex = loadResource("/contracts/ISTCoin.bin");
        String constructorArg = "000000000000000000000000" + deployer.toUnprefixedHexString();
        Bytes initcode = Bytes.fromHexString(bytecodeHex + constructorArg);

        Transaction deployTx = Transaction.create(deployer, null, Wei.ZERO,
                initcode, GAS_PRICE, 5_000_000L, 0);
        EvmResult deployResult = evm.executeTransaction(deployTx);
        assertTrue(deployResult.success());

        Address contractAddress = deployResult.deployedAddress().get();
        Wei balanceBeforeOog = evm.getBalance(deployer);
        long tinyGasLimit = 100L; // way too low for any EVM call

        Bytes callData = Bytes.fromHexString("18160ddd"); // totalSupply()
        Transaction oogTx = Transaction.create(deployer, contractAddress, Wei.ZERO,
                callData, GAS_PRICE, tinyGasLimit, evm.getNonce(deployer));

        EvmResult oogResult = evm.executeTransaction(oogTx);

        assertFalse(oogResult.success(), "OOG call must fail");
        assertEquals(tinyGasLimit, oogResult.gasUsed(),
                "gasUsed must equal gasLimit when out-of-gas (spec: no refund)");

        Wei expectedFee = Wei.of(GAS_PRICE * tinyGasLimit);
        assertEquals(expectedFee, oogResult.fee());
        assertEquals(balanceBeforeOog.subtract(expectedFee), evm.getBalance(deployer),
                "Fee deducted even on OOG abort");
    }

    // -------------------------------------------------------------------------
    // 5. Transaction validation — gasPrice and gasLimit must be > 0
    // -------------------------------------------------------------------------

    @Test
    void gasPriceZero_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY,
                        0L /*gasPrice=0*/, 21_000L, 0));
    }

    @Test
    void gasLimitZero_throwsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY,
                        GAS_PRICE, 0L /*gasLimit=0*/, 0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String loadResource(String path) throws Exception {
        try (InputStream is = GasFeeTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
