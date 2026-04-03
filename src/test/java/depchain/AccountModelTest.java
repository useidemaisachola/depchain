package depchain;

import depchain.blockchain.Account;
import depchain.blockchain.AccountType;
import depchain.blockchain.EvmService;
import depchain.crypto.CryptoUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the EOA / Contract account model.
 *
 * Covers:
 *  1. AccountType enum values
 *  2. Account record construction, field access, and immutability
 *  3. EvmService.deriveAddress() — determinism, size, uniqueness
 *  4. EvmService.getAccount()  — EOA and Contract type discrimination,
 *     balance/nonce, unknown addresses
 */
class AccountModelTest {

    private static final Address ALICE =
            Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB =
            Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    private EvmService evm;

    @BeforeEach
    void setUp() {
        evm = new EvmService();
    }

    // -------------------------------------------------------------------------
    // AccountType enum
    // -------------------------------------------------------------------------

    @Test
    void accountTypeEnumHasTwoValues() {
        AccountType[] values = AccountType.values();
        assertEquals(2, values.length);
        assertEquals(AccountType.EOA,      AccountType.valueOf("EOA"));
        assertEquals(AccountType.CONTRACT, AccountType.valueOf("CONTRACT"));
    }

    // -------------------------------------------------------------------------
    // Account record
    // -------------------------------------------------------------------------

    @Test
    void accountRecordStoresAllFields() {
        Map<UInt256, UInt256> storage = Map.of(UInt256.ONE, UInt256.valueOf(42));
        Account account = new Account(AccountType.CONTRACT, ALICE,
                Wei.of(500L), 3L, Bytes.fromHexString("6001"), storage);

        assertEquals(AccountType.CONTRACT, account.type());
        assertEquals(ALICE,                account.address());
        assertEquals(Wei.of(500L),         account.balance());
        assertEquals(3L,                   account.nonce());
        assertEquals(Bytes.fromHexString("6001"), account.code());
        assertEquals(UInt256.valueOf(42),   account.storage().get(UInt256.ONE));
    }

    @Test
    void accountIsEoaAndIsContractHelpers() {
        Account eoa = new Account(AccountType.EOA, ALICE,
                Wei.ZERO, 0L, Bytes.EMPTY, Collections.emptyMap());
        Account contract = new Account(AccountType.CONTRACT, BOB,
                Wei.ZERO, 0L, Bytes.fromHexString("60"), Collections.emptyMap());

        assertTrue(eoa.isEoa());
        assertFalse(eoa.isContract());
        assertTrue(contract.isContract());
        assertFalse(contract.isEoa());
    }

    @Test
    void accountStorageIsUnmodifiable() {
        Map<UInt256, UInt256> mutable = new HashMap<>();
        mutable.put(UInt256.ZERO, UInt256.ONE);
        Account account = new Account(AccountType.CONTRACT, ALICE,
                Wei.ZERO, 0L, Bytes.EMPTY, mutable);

        assertThrows(UnsupportedOperationException.class,
                () -> account.storage().put(UInt256.ONE, UInt256.valueOf(99)));
    }

    @Test
    void accountNullTypeThrows() {
        assertThrows(NullPointerException.class,
                () -> new Account(null, ALICE, Wei.ZERO, 0L,
                        Bytes.EMPTY, Collections.emptyMap()));
    }

    @Test
    void accountNullAddressThrows() {
        assertThrows(NullPointerException.class,
                () -> new Account(AccountType.EOA, null, Wei.ZERO, 0L,
                        Bytes.EMPTY, Collections.emptyMap()));
    }

    // -------------------------------------------------------------------------
    // EvmService.deriveAddress()
    // -------------------------------------------------------------------------

    @Test
    void deriveAddressIsDeterministic() {
        KeyPair kp = CryptoUtils.generateKeyPair();
        Address a1 = EvmService.deriveAddress(kp.getPublic());
        Address a2 = EvmService.deriveAddress(kp.getPublic());
        assertEquals(a1, a2);
    }

    @Test
    void deriveAddressIs20Bytes() {
        KeyPair kp = CryptoUtils.generateKeyPair();
        Address addr = EvmService.deriveAddress(kp.getPublic());
        assertEquals(20, addr.size());
    }

    @Test
    void deriveAddressDiffersForDifferentKeys() {
        Address a1 = EvmService.deriveAddress(CryptoUtils.generateKeyPair().getPublic());
        Address a2 = EvmService.deriveAddress(CryptoUtils.generateKeyPair().getPublic());
        assertNotEquals(a1, a2);
    }

    // -------------------------------------------------------------------------
    // EvmService.getAccount()
    // -------------------------------------------------------------------------

    @Test
    void getAccountReturnsNullForUnknownAddress() {
        assertNull(evm.getAccount(ALICE));
    }

    @Test
    void getAccountForEoaHasCorrectType() {
        Wei balance = Wei.of(new BigInteger("1000000000000000000"));
        evm.createAccount(ALICE, balance);

        Account account = evm.getAccount(ALICE);

        assertNotNull(account);
        assertEquals(AccountType.EOA, account.type());
        assertTrue(account.isEoa());
        assertFalse(account.isContract());
        assertTrue(account.code().isEmpty(), "EOA must have no code");
        assertTrue(account.storage().isEmpty(), "EOA must have no storage");
    }

    @Test
    void getAccountBalanceMatchesCreateAccount() {
        Wei balance = Wei.of(12345L);
        evm.createAccount(ALICE, balance);

        assertEquals(balance, evm.getAccount(ALICE).balance());
    }

    @Test
    void eoaNonceStartsAtZero() {
        evm.createAccount(ALICE, Wei.ZERO);
        assertEquals(0L, evm.getAccount(ALICE).nonce());
    }

    @Test
    void getAccountForContractHasCorrectType() throws Exception {
        evm.createAccount(ALICE, Wei.of(new BigInteger("1000000000000000000")));

        String hex = loadResource("/contracts/ISTCoin.bin");
        String constructorArg = "000000000000000000000000"
                + ALICE.toUnprefixedHexString();
        Bytes initcode = Bytes.fromHexString(hex + constructorArg);

        Address contractAddr = evm.deployContract(ALICE, initcode, 5_000_000L);
        Account account = evm.getAccount(contractAddr);

        assertNotNull(account);
        assertEquals(AccountType.CONTRACT, account.type());
        assertTrue(account.isContract());
        assertFalse(account.code().isEmpty(), "Contract must have non-empty code");
    }

    @Test
    void contractAccountStorageIsNonEmpty() throws Exception {
        evm.createAccount(ALICE, Wei.of(new BigInteger("1000000000000000000")));

        String hex = loadResource("/contracts/ISTCoin.bin");
        String constructorArg = "000000000000000000000000"
                + ALICE.toUnprefixedHexString();
        Bytes initcode = Bytes.fromHexString(hex + constructorArg);

        Address contractAddr = evm.deployContract(ALICE, initcode, 5_000_000L);
        Account account = evm.getAccount(contractAddr);

        // ISTCoin constructor writes totalSupply (slot 0) and _balances[ALICE]
        assertFalse(account.storage().isEmpty(),
                "ISTCoin contract should have non-empty storage after deployment");
    }

    @Test
    void deriveAddressFromKeyThenCreateAndRetrieve() {
        KeyPair kp = CryptoUtils.generateKeyPair();
        Address addr = EvmService.deriveAddress(kp.getPublic());

        evm.createAccount(addr, Wei.of(999L));
        Account account = evm.getAccount(addr);

        assertNotNull(account);
        assertEquals(addr, account.address());
        assertEquals(AccountType.EOA, account.type());
        assertEquals(Wei.of(999L), account.balance());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String loadResource(String path) throws Exception {
        try (InputStream is = AccountModelTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
