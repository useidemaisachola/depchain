package depchain.blockchain;

import depchain.crypto.CryptoUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable DepChain transaction.
 *
 * <p>Three kinds of transaction are supported:
 * <ul>
 *   <li><b>DepCoin transfer</b>  — {@code to} is a non-null address, {@code data} is empty.</li>
 *   <li><b>Smart contract call</b> — {@code to} is a contract address, {@code data} is ABI-encoded calldata.</li>
 *   <li><b>Contract deployment</b> — {@code to} is {@code null}, {@code data} is the constructor initcode.</li>
 * </ul>
 *
 * <p>Gas fee charged to the sender:
 * <pre>  fee = gasPrice × min(gasLimit, gasUsed)  (in Wei)</pre>
 *
 * <p>Replay protection: each transaction carries a per-sender {@code nonce}
 * (must equal the sender account's current nonce) and a globally unique
 * {@code transactionId} (UUID).
 *
 * <p>The sender proves ownership of the {@code from} account by signing
 * {@link #bytesToSign()} with their RSA private key. Verify with
 * {@link #verifySignature(PublicKey)}.
 */
public final class Transaction implements Serializable {

    private static final long serialVersionUID = 1L;

    // All fields stored as basic Java types for reliable serialization.
    private final String  transactionId; // UUID string
    private final String  from;          // "0x" + 40 hex chars
    private final String  to;            // "0x" + 40 hex chars, or null for deployment
    private final String  value;         // Wei decimal string (BigInteger)
    private final String  data;          // "0x" hex (empty string == Bytes.EMPTY)
    private final long    gasPrice;      // Wei per gas unit, must be > 0
    private final long    gasLimit;      // max gas units, must be > 0
    private final long    nonce;         // sender nonce for replay protection
    private final byte[]  signature;     // RSA signature, null if unsigned

 
    private Transaction(String transactionId, String from, String to,
                        String value, String data, long gasPrice,
                        long gasLimit, long nonce, byte[] signature) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(from,          "from must not be null");
        Objects.requireNonNull(value,         "value must not be null");
        Objects.requireNonNull(data,          "data must not be null");
        if (gasPrice <= 0) throw new IllegalArgumentException("gasPrice must be > 0");
        if (gasLimit <= 0) throw new IllegalArgumentException("gasLimit must be > 0");

        this.transactionId = transactionId;
        this.from          = from;
        this.to            = to;
        this.value         = value;
        this.data          = data;
        this.gasPrice      = gasPrice;
        this.gasLimit      = gasLimit;
        this.nonce         = nonce;
        this.signature     = signature == null ? null : signature.clone();
    }

    /**
     * Creates an <em>unsigned</em> transaction with a freshly generated UUID.
     *
     * @param from      sender's account address
     * @param to        recipient address, or {@code null} for contract deployment
     * @param value     DepCoin amount to transfer (use {@link Wei#ZERO} for pure calls)
     * @param data      ABI calldata or initcode (use {@link Bytes#EMPTY} for plain transfers)
     * @param gasPrice  Wei per gas unit, must be &gt; 0
     * @param gasLimit  maximum gas units, must be &gt; 0
     * @param nonce     sender nonce (must match the account's current nonce)
     */
    public static Transaction create(Address from, Address to, Wei value,
                                     Bytes data, long gasPrice, long gasLimit,
                                     long nonce) {
        Objects.requireNonNull(from,  "from must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(data,  "data must not be null");

        return new Transaction(
                UUID.randomUUID().toString(),
                from.toHexString().toLowerCase(),
                to == null ? null : to.toHexString().toLowerCase(),
                value.getAsBigInteger().toString(),
                data.isEmpty() ? "" : data.toHexString(),
                gasPrice,
                gasLimit,
                nonce,
                null);
    }

 
    public String    getTransactionId() { return transactionId; }
    public Address   getFrom()          { return Address.fromHexString(from); }
    public long      getGasPrice()      { return gasPrice; }
    public long      getGasLimit()      { return gasLimit; }
    public long      getNonce()         { return nonce; }
    public byte[]    getSignature()     { return signature == null ? null : signature.clone(); }
    public boolean   isSigned()         { return signature != null; }

  
    public Optional<Address> getTo() {
        return to == null ? Optional.empty() : Optional.of(Address.fromHexString(to));
    }

    public Wei getValue() {
        return Wei.of(new BigInteger(value));
    }

    public Bytes getData() {
        return data.isEmpty() ? Bytes.EMPTY : Bytes.fromHexString(data);
    }

 
    /** {@code true} when this transaction deploys a new contract ({@code to} is null). */
    public boolean isDeployment() {
        return to == null;
    }

    /** {@code true} when this transaction calls a deployed contract. */
    public boolean isContractCall() {
        return to != null && !data.isEmpty();
    }

    /**
     * {@code true} when this is a plain DepCoin transfer (non-null {@code to},
     * empty {@code data}).
     */
    public boolean isTransfer() {
        return to != null && data.isEmpty();
    }

  
    /**
     * Computes the transaction fee charged to the sender.
     *
     * <pre>  fee = gasPrice × min(gasLimit, gasUsed)</pre>
     *
     * @param gasUsed actual gas consumed during execution (determined by the EVM)
     * @return the fee in Wei deducted from the sender's DepCoin balance
     */
    public Wei gasFee(long gasUsed) {
        return Wei.of(gasPrice * Math.min(gasLimit, gasUsed));
    }

  
    /**
     * Returns the canonical byte representation that the sender must sign.
     *
     * <p>Includes all fields <em>except</em> the signature, concatenated as
     * UTF-8 with {@code |} separators. The format is fixed so every node
     * produces identical bytes for the same transaction.
     */
    public byte[] bytesToSign() {
        String canonical = String.join("|",
                transactionId,
                from,
                to == null ? "null" : to,
                value,
                data.isEmpty() ? "0x" : data,
                Long.toString(gasPrice),
                Long.toString(gasLimit),
                Long.toString(nonce));
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs this transaction and returns a new {@link Transaction} that carries
     * the RSA signature. The original (unsigned) transaction is not modified.
     *
     * @param privateKey sender's RSA private key
     * @return a new signed copy of this transaction
     */
    public Transaction sign(PrivateKey privateKey) {
        byte[] sig = CryptoUtils.sign(bytesToSign(), privateKey);
        return new Transaction(transactionId, from, to, value, data,
                gasPrice, gasLimit, nonce, sig);
    }

    /**
     * Verifies that the {@link #getSignature()} is a valid RSA signature of
     * {@link #bytesToSign()} under {@code publicKey}.
     *
     * @param publicKey sender's RSA public key (must correspond to {@link #getFrom()})
     * @return {@code true} if the signature is present and valid
     */
    public boolean verifySignature(PublicKey publicKey) {
        if (signature == null) return false;
        return CryptoUtils.verify(bytesToSign(), signature, publicKey);
    }

    /**
     * Validates basic transaction properties.
     * 
     * @return null if valid, or error message if invalid
     */
    public String validateBasicFields() {
        if (gasPrice <= 0) {
            return "gasPrice must be > 0";
        }
        if (gasLimit <= 0) {
            return "gasLimit must be > 0";
        }
        try {
            new BigInteger(value);
        } catch (Exception e) {
            return "invalid value format: " + e.getMessage();
        }
        return null;
    }

 
    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Transaction serialization failed", e);
        }
    }

    public static Transaction deserialize(byte[] bytes) {
        try (ObjectInputStream ois =
                     new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (Transaction) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Transaction deserialization failed", e);
        }
    }

 
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction t)) return false;
        return gasPrice == t.gasPrice
                && gasLimit == t.gasLimit
                && nonce == t.nonce
                && transactionId.equals(t.transactionId)
                && from.equals(t.from)
                && Objects.equals(to, t.to)
                && value.equals(t.value)
                && data.equals(t.data)
                && Arrays.equals(signature, t.signature);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(transactionId, from, to, value, data,
                gasPrice, gasLimit, nonce);
        result = 31 * result + Arrays.hashCode(signature);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
                "Transaction{id=%s, from=%s, to=%s, value=%s, gasPrice=%d, " +
                "gasLimit=%d, nonce=%d, signed=%b}",
                transactionId, from, to == null ? "null(deploy)" : to,
                value, gasPrice, gasLimit, nonce, isSigned());
    }
}
