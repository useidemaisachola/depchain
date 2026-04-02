package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import depchain.crypto.CryptoUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable representation of the genesis block (block 0).
 *
 * <p>Structure matches the project spec:
 * <pre>
 * {
 *   "block_hash": "0x...",
 *   "previous_block_hash": null,
 *   "transactions": [ { "from": "0x...", "to": null, "input": "0x...",
 *                        "gas_price": 1, "gas_limit": 5000000 } ],
 *   "state": {
 *     "0x...": { "balance": "1000000000000000000", "nonce": 0,
 *                "code": "", "storage": {} }
 *   }
 * }
 * </pre>
 *
 * <p>The {@code block_hash} is the hex-encoded SHA-256 digest of the canonical
 * JSON of the block content (all fields except {@code block_hash} itself).
 */
public class GenesisBlock {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("block_hash")
    private String blockHash;

    @SerializedName("previous_block_hash")
    private final String previousBlockHash = null;   // always null for genesis

    private final List<TransactionEntry> transactions;

    private final Map<String, AccountEntry> state;

    GenesisBlock(List<TransactionEntry> transactions, Map<String, AccountEntry> state) {
        this.transactions = transactions;
        this.state        = state;
        this.blockHash    = computeHash();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getBlockHash()         { return blockHash; }
    public String getPreviousBlockHash() { return previousBlockHash; }
    public List<TransactionEntry> getTransactions() { return transactions; }
    public Map<String, AccountEntry>    getState()        { return state; }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Serializes this genesis block to pretty-printed JSON. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** Deserializes a genesis block from JSON produced by {@link #toJson()}. */
    public static GenesisBlock fromJson(String json) {
        return GSON.fromJson(json, GenesisBlock.class);
    }

    // -------------------------------------------------------------------------
    // Hash
    // -------------------------------------------------------------------------

    /**
     * Computes the block hash as the hex-encoded SHA-256 digest of the
     * canonical JSON of this block's content (excluding the hash field itself).
     */
    private String computeHash() {
        // Temporarily null out the hash so it is not included in the digest input.
        ContentForHash content = new ContentForHash(previousBlockHash, transactions, state);
        byte[] canonical = GSON.toJson(content).getBytes(StandardCharsets.UTF_8);
        byte[] digest    = CryptoUtils.hash(canonical);
        return toHex(digest);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /** Minimal representation used as canonical hash input (no hash field). */
    private static final class ContentForHash {
        @SerializedName("previous_block_hash") final String previousBlockHash;
        final List<TransactionEntry>                         transactions;
        final Map<String, AccountEntry>                      state;

        ContentForHash(String prev, List<TransactionEntry> txs, Map<String, AccountEntry> s) {
            this.previousBlockHash = prev;
            this.transactions      = txs;
            this.state             = s;
        }
    }

    /** One transaction entry in the genesis block. */
    public static final class TransactionEntry {
        public final String from;
        public final String to;       // null for deployment
        public final String input;    // initcode hex with "0x" prefix
        @SerializedName("gas_price") public final long gasPrice;
        @SerializedName("gas_limit") public final long gasLimit;

        public TransactionEntry(String from, String to, String input,
                                long gasPrice, long gasLimit) {
            this.from     = from;
            this.to       = to;
            this.input    = input;
            this.gasPrice = gasPrice;
            this.gasLimit = gasLimit;
        }
    }

    /** Account state entry in the genesis block's world state. */
    public static final class AccountEntry {
        public final String balance;
        public final long   nonce;
        public final String code;     // hex; empty string for EOA
        public final Map<String, String> storage; // hex slot → hex value

        public AccountEntry(String balance, long nonce,
                            String code, Map<String, String> storage) {
            this.balance = balance;
            this.nonce   = nonce;
            this.code    = code;
            this.storage = storage;
        }
    }
}
