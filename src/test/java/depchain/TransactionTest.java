package depchain;

import depchain.blockchain.Transaction;
import depchain.crypto.CryptoUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Transaction:
 *  1. Creation and field accessors
 *  2. Transaction type detection
 *  3. Signing and signature verification
 *  4. Gas fee calculation
 *  5. Serialization round-trip
 *  6. bytesToSign determinism
 *  7. Validation guards
 */
class TransactionTest {

    private static final Address ALICE =
            Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB =
            Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address CONTRACT =
            Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    private static KeyPair aliceKeys;
    private static KeyPair bobKeys;

    @BeforeAll
    static void generateKeys() {
        aliceKeys = CryptoUtils.generateKeyPair();
        bobKeys   = CryptoUtils.generateKeyPair();
    }

    // -------------------------------------------------------------------------
    // Creation and accessors
    // -------------------------------------------------------------------------

    @Test
    void createTransferHasCorrectFields() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1000L),
                Bytes.EMPTY, 1L, 21000L, 0L);

        assertNotNull(tx.getTransactionId());
        assertEquals(ALICE, tx.getFrom());
        assertTrue(tx.getTo().isPresent());
        assertEquals(BOB, tx.getTo().get());
        assertEquals(Wei.of(1000L), tx.getValue());
        assertEquals(Bytes.EMPTY, tx.getData());
        assertEquals(1L, tx.getGasPrice());
        assertEquals(21000L, tx.getGasLimit());
        assertEquals(0L, tx.getNonce());
        assertFalse(tx.isSigned());
        assertNull(tx.getSignature());
    }

    @Test
    void createDeploymentHasNullTo() {
        Transaction tx = Transaction.create(ALICE, null, Wei.ZERO,
                Bytes.fromHexString("0x6001"), 1L, 100000L, 0L);

        assertTrue(tx.getTo().isEmpty());
        assertNull(tx.getTo().orElse(null));
    }

    @Test
    void eachTransactionHasUniqueId() {
        Transaction tx1 = Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction tx2 = Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 1L, 21000L, 0L);
        assertNotEquals(tx1.getTransactionId(), tx2.getTransactionId());
    }

    // -------------------------------------------------------------------------
    // Transaction type detection
    // -------------------------------------------------------------------------

    @Test
    void transferDetection() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        assertTrue(tx.isTransfer());
        assertFalse(tx.isContractCall());
        assertFalse(tx.isDeployment());
    }

    @Test
    void contractCallDetection() {
        Transaction tx = Transaction.create(ALICE, CONTRACT, Wei.ZERO,
                Bytes.fromHexString("0xa9059cbb"), 1L, 50000L, 0L);
        assertTrue(tx.isContractCall());
        assertFalse(tx.isTransfer());
        assertFalse(tx.isDeployment());
    }

    @Test
    void deploymentDetection() {
        Transaction tx = Transaction.create(ALICE, null, Wei.ZERO,
                Bytes.fromHexString("0x6001600055"), 1L, 200000L, 0L);
        assertTrue(tx.isDeployment());
        assertFalse(tx.isTransfer());
        assertFalse(tx.isContractCall());
    }

    // -------------------------------------------------------------------------
    // Signing and verification
    // -------------------------------------------------------------------------

    @Test
    void unsignedTransactionIsNotSigned() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        assertFalse(tx.isSigned());
        assertFalse(tx.verifySignature(aliceKeys.getPublic()));
    }

    @Test
    void signedTransactionVerifiesCorrectly() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(500L), Bytes.EMPTY, 1L, 21000L, 3L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());

        assertTrue(signed.isSigned());
        assertNotNull(signed.getSignature());
        assertTrue(signed.verifySignature(aliceKeys.getPublic()));
    }

    @Test
    void signatureFailsWithWrongPublicKey() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());

        assertFalse(signed.verifySignature(bobKeys.getPublic()));
    }

    @Test
    void signingDoesNotMutateOriginal() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());

        assertFalse(tx.isSigned());
        assertTrue(signed.isSigned());
    }

    @Test
    void signedTransactionPreservesAllFields() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(999L), Bytes.EMPTY, 2L, 30000L, 5L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());

        assertEquals(tx.getTransactionId(), signed.getTransactionId());
        assertEquals(tx.getFrom(), signed.getFrom());
        assertEquals(tx.getTo(), signed.getTo());
        assertEquals(tx.getValue(), signed.getValue());
        assertEquals(tx.getData(), signed.getData());
        assertEquals(tx.getGasPrice(), signed.getGasPrice());
        assertEquals(tx.getGasLimit(), signed.getGasLimit());
        assertEquals(tx.getNonce(), signed.getNonce());
    }

    @Test
    void signatureIsolatedFromExternalMutation() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());

        byte[] sig = signed.getSignature();
        sig[0] ^= 0xFF; // mutate the returned copy

        // Verification must still pass — internal state not mutated
        assertTrue(signed.verifySignature(aliceKeys.getPublic()));
    }

    // -------------------------------------------------------------------------
    // Gas fee
    // -------------------------------------------------------------------------

    @Test
    void gasFeeWhenGasUsedUnderLimit() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 10L, 21000L, 0L);
        assertEquals(Wei.of(100L), tx.gasFee(10L));   // 10 * min(21000, 10) = 100
    }

    @Test
    void gasFeeWhenGasUsedOverLimit() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 10L, 21000L, 0L);
        assertEquals(Wei.of(210000L), tx.gasFee(30000L)); // 10 * min(21000, 30000) = 210000
    }

    @Test
    void gasFeeWhenGasUsedEqualsLimit() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 5L, 21000L, 0L);
        assertEquals(Wei.of(105000L), tx.gasFee(21000L)); // 5 * 21000
    }

    // -------------------------------------------------------------------------
    // bytesToSign determinism
    // -------------------------------------------------------------------------

    @Test
    void bytesToSignIsDeterministic() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        byte[] b1 = tx.bytesToSign();
        byte[] b2 = tx.bytesToSign();
        assertArrayEquals(b1, b2);
    }

    @Test
    void bytesToSignDiffersForDifferentTransactions() {
        Transaction tx1 = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction tx2 = Transaction.create(ALICE, BOB, Wei.of(2L), Bytes.EMPTY, 1L, 21000L, 0L);
        // Different value → different bytes (also different UUIDs, but testing structural diff)
        assertFalse(Arrays.equals(tx1.bytesToSign(), tx2.bytesToSign()));
    }

    // -------------------------------------------------------------------------
    // Serialization round-trip
    // -------------------------------------------------------------------------

    @Test
    void unsignedTransactionSerializesAndDeserializes() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(42L), Bytes.EMPTY, 1L, 21000L, 7L);
        byte[] bytes = tx.serialize();
        Transaction restored = Transaction.deserialize(bytes);

        assertEquals(tx.getTransactionId(), restored.getTransactionId());
        assertEquals(tx.getFrom(), restored.getFrom());
        assertEquals(tx.getTo(), restored.getTo());
        assertEquals(tx.getValue(), restored.getValue());
        assertEquals(tx.getData(), restored.getData());
        assertEquals(tx.getGasPrice(), restored.getGasPrice());
        assertEquals(tx.getGasLimit(), restored.getGasLimit());
        assertEquals(tx.getNonce(), restored.getNonce());
        assertFalse(restored.isSigned());
    }

    @Test
    void signedTransactionSerializesAndDeserializes() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L)
                .sign(aliceKeys.getPrivate());

        byte[] bytes = tx.serialize();
        Transaction restored = Transaction.deserialize(bytes);

        assertTrue(restored.isSigned());
        assertTrue(restored.verifySignature(aliceKeys.getPublic()));
    }

    @Test
    void deploymentTransactionSerializesAndDeserializes() {
        Bytes initcode = Bytes.fromHexString("0x6001600055");
        Transaction tx = Transaction.create(ALICE, null, Wei.ZERO, initcode, 1L, 200000L, 0L);
        Transaction restored = Transaction.deserialize(tx.serialize());

        assertTrue(restored.isDeployment());
        assertEquals(initcode, restored.getData());
        assertTrue(restored.getTo().isEmpty());
    }

    @Test
    void contractCallTransactionSerializesAndDeserializes() {
        Bytes calldata = Bytes.fromHexString("0xa9059cbb" +
                "000000000000000000000000bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                "0000000000000000000000000000000000000000000000000000000000000064");
        Transaction tx = Transaction.create(ALICE, CONTRACT, Wei.ZERO, calldata, 1L, 50000L, 2L);
        Transaction restored = Transaction.deserialize(tx.serialize());

        assertTrue(restored.isContractCall());
        assertEquals(calldata, restored.getData());
        assertEquals(CONTRACT, restored.getTo().get());
    }

    // -------------------------------------------------------------------------
    // Validation guards
    // -------------------------------------------------------------------------

    @Test
    void createWithZeroGasPriceThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 0L, 21000L, 0L));
    }

    @Test
    void createWithNegativeGasPriceThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, -1L, 21000L, 0L));
    }

    @Test
    void createWithZeroGasLimitThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, Bytes.EMPTY, 1L, 0L, 0L));
    }

    @Test
    void createWithNullFromThrows() {
        assertThrows(NullPointerException.class, () ->
                Transaction.create(null, BOB, Wei.ZERO, Bytes.EMPTY, 1L, 21000L, 0L));
    }

    @Test
    void createWithNullValueThrows() {
        assertThrows(NullPointerException.class, () ->
                Transaction.create(ALICE, BOB, null, Bytes.EMPTY, 1L, 21000L, 0L));
    }

    @Test
    void createWithNullDataThrows() {
        assertThrows(NullPointerException.class, () ->
                Transaction.create(ALICE, BOB, Wei.ZERO, null, 1L, 21000L, 0L));
    }

    // -------------------------------------------------------------------------
    // equals / hashCode
    // -------------------------------------------------------------------------

    @Test
    void equalsAndHashCodeConsistentForSameTransaction() {
        Transaction tx = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        byte[] bytes = tx.serialize();
        Transaction copy = Transaction.deserialize(bytes);

        assertEquals(tx, copy);
        assertEquals(tx.hashCode(), copy.hashCode());
    }

    @Test
    void signedAndUnsignedTransactionsAreNotEqual() {
        Transaction tx     = Transaction.create(ALICE, BOB, Wei.of(1L), Bytes.EMPTY, 1L, 21000L, 0L);
        Transaction signed = tx.sign(aliceKeys.getPrivate());
        assertNotEquals(tx, signed);
    }
}
