package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import depchain.crypto.CryptoUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable representation of a committed block (height ≥ 0).
 *
 * <p>Structure:
 * <pre>
 * {
 *   "block_hash": "0x...",
 *   "previous_block_hash": "0x..." | null,
 *   "height": 1,
 *   "transactions": [ { "from": "0x...", "to": "0x...", "input": "0x...",
 *                        "gas_price": 1, "gas_limit": 5000000,
 *                        "gas_used": 21000, "fee": "21000", "success": true } ],
 *   "world_state": {
 *     "0x...": { "balance": "1000000000000000000", "nonce": 0,
 *                "code": "", "storage": {} }
 *   }
 * }
 * </pre>
 */
public class PersistedBlock {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("block_hash")
    private String blockHash;

    @SerializedName("previous_block_hash")
    private final String previousBlockHash;

    private final int height;
    private final List<TransactionEntry> transactions;

    @SerializedName("world_state")
    private final Map<String, AccountEntry> worldState;

    public PersistedBlock(String previousBlockHash, int height,
                          List<TransactionEntry> transactions,
                          Map<String, AccountEntry> worldState) {
        this.previousBlockHash = previousBlockHash;
        this.height            = height;
        this.transactions      = transactions;
        this.worldState        = worldState;
        this.blockHash         = computeHash();
    }

  

    public String getBlockHash()                          { return blockHash; }
    public String getPreviousBlockHash()                  { return previousBlockHash; }
    public int    getHeight()                             { return height; }
    public List<TransactionEntry> getTransactions()       { return transactions; }
    public Map<String, AccountEntry> getWorldState()      { return worldState; }



    public String toJson() { return GSON.toJson(this); }

    public static PersistedBlock fromJson(String json) {
        return GSON.fromJson(json, PersistedBlock.class);
    }


    private String computeHash() {
        ContentForHash content = new ContentForHash(previousBlockHash, height, transactions, worldState);
        byte[] canonical = GSON.toJson(content).getBytes(StandardCharsets.UTF_8);
        byte[] digest    = CryptoUtils.hash(canonical);
        return toHex(digest);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

  
    private static final class ContentForHash {
        @SerializedName("previous_block_hash") final String previousBlockHash;
        final int height;
        final List<TransactionEntry> transactions;
        @SerializedName("world_state") final Map<String, AccountEntry> worldState;

        ContentForHash(String prev, int h, List<TransactionEntry> txs, Map<String, AccountEntry> ws) {
            this.previousBlockHash = prev;
            this.height            = h;
            this.transactions      = txs;
            this.worldState        = ws;
        }
    }

    /** One transaction entry in a persisted block. */
    public static final class TransactionEntry {
        public final String  from;
        public final String  to;       // null for deployment
        public final String  input;    // hex calldata / initcode
        @SerializedName("gas_price")  public final long    gasPrice;
        @SerializedName("gas_limit")  public final long    gasLimit;
        @SerializedName("gas_used")   public final long    gasUsed;
        public final String  fee;      // Wei decimal string
        public final boolean success;

        public TransactionEntry(String from, String to, String input,
                                long gasPrice, long gasLimit,
                                long gasUsed, String fee, boolean success) {
            this.from     = from;
            this.to       = to;
            this.input    = input;
            this.gasPrice = gasPrice;
            this.gasLimit = gasLimit;
            this.gasUsed  = gasUsed;
            this.fee      = fee;
            this.success  = success;
        }
    }

    /** Account state entry in a block's world state. */
    public static final class AccountEntry {
        public final String              balance;
        public final long                nonce;
        public final String              code;     // hex; empty string for EOA
        public final Map<String, String> storage;  // hex slot → hex value

        public AccountEntry(String balance, long nonce,
                            String code, Map<String, String> storage) {
            this.balance = balance;
            this.nonce   = nonce;
            this.code    = code;
            this.storage = storage;
        }
    }
}
