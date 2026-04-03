package depchain;

import depchain.blockchain.EvmService;
import depchain.blockchain.Transaction;
import depchain.client.BlockchainClient;
import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;
import depchain.net.fault.NetworkFaultController;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byzantine client attack scenarios.
 *
 * Each test starts all 4 nodes with genesis loaded so that node-derived addresses
 * have a funded DepCoin balance. Tests demonstrate that the system correctly rejects:
 *
 *  1. A transaction where {@code from} does not match the key signing the request
 *     (sender authorization / identity spoofing attack).
 *  2. A transaction with the wrong nonce (future or past nonce).
 *  3. A transaction from an account with insufficient balance.
 *  4. A replayed transaction (same nonce re-submitted after the first committed).
 *  5. A double-spend attempt: two transactions from the same sender submitted
 *     back-to-back; the second is rejected by the pending-pool guard.
 */
class ByzantineClientTest {

    private static final int BASE_CLIENT_PORT = 6400;
    private int nextClientPort = BASE_CLIENT_PORT;

    private final List<Node> nodesToStop = new ArrayList<>();
    private final List<BlockchainClient> clientsToStop = new ArrayList<>();
    private Path stateDirectory;
    private Map<Integer, KeyManager> keyManagers;

    @BeforeEach
    void setUp() throws Exception {
        stateDirectory = Files.createTempDirectory("depchain-byz-test-");
        NetworkFaultController.clearRules();
        keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        for (int id = 0; id < NetworkConfig.NUM_NODES; id++) {
            Node node = new Node(id, keyManagers.get(id), ByzantineBehavior.HONEST,
                    stateDirectory.toString(), true);
            node.start();
            nodesToStop.add(node);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (BlockchainClient client : clientsToStop) {
            try { client.stop(); } catch (Exception ignored) {}
        }
        clientsToStop.clear();
        for (Node node : nodesToStop) {
            try { node.stop(); } catch (Exception ignored) {}
        }
        nodesToStop.clear();
        NetworkFaultController.clearRules();
    }

    // -------------------------------------------------------------------------
    // Test 1 – Sender authorization: from address ≠ signing key's address
    // -------------------------------------------------------------------------

    /**
     * A Byzantine client submits a transaction where {@code tx.from} is set to a
     * funded address (node 0's EVM address) but the ClientRequest is signed with the
     * attacker's own key.  The node must reject this because the derived address of
     * the signing key does not match {@code tx.from}.
     */
    @Test
    void byzantineClient_spoofedFromAddress_isRejected() throws Exception {
        BlockchainClient attacker = createClient(); // fresh key, derived address has no balance

        // Funded target address belongs to node 0's key – attacker doesn't own it.
        Address fundedVictim = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));

        // Build a transaction claiming to originate from the victim's address.
        Transaction maliciousTx = Transaction.create(
                fundedVictim, freshAddress(), Wei.of(1_000), Bytes.EMPTY, 1L, 21_000L, 0L);
        // Encode and submit; the ClientRequest will be signed with the attacker's own key.
        String data = Base64.getEncoder().encodeToString(maliciousTx.serialize());

        boolean result = attacker.submitRequest(data);

        assertFalse(result,
                "Node must reject a transaction where from != address derived from the signing key");
    }

    // -------------------------------------------------------------------------
    // Test 2 – Wrong nonce
    // -------------------------------------------------------------------------

    /**
     * A client submits a transaction whose nonce is higher than the sender's current
     * on-chain nonce.  The node must reject it at the pre-validation stage.
     */
    @Test
    void byzantineClient_futureNonce_isRejected() throws Exception {
        BlockchainClient client = createClientWithKey(0);
        Address sender = client.getEvmAddress(); // genesis-funded

        // Submit a nonce far ahead of the current on-chain value.
        long nonce = currentNonce(sender) + 100;
        Transaction tx = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, nonce);

        boolean result = client.submitTransaction(tx);

        assertFalse(result, "Transaction with nonce ahead of the account's current nonce must be rejected");
    }

    /**
     * A client submits a transaction with a nonce that is behind the current on-chain
     * nonce (stale nonce after a previous transaction committed).
     */
    @Test
    void byzantineClient_staleNonce_isRejected() throws Exception {
        BlockchainClient client = createClientWithKey(0);
        Address sender = client.getEvmAddress();

        long nonce = currentNonce(sender);
        // Commit the first transaction — nonce advances by 1.
        Transaction tx0 = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, nonce);
        assertTrue(client.submitTransaction(tx0), "First transaction must commit");

        // Re-submit with the now-stale nonce (already used by the committed tx).
        Transaction stale = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, nonce);

        boolean result = client.submitTransaction(stale);

        assertFalse(result, "Transaction with a stale (already-used) nonce must be rejected");
    }

    // -------------------------------------------------------------------------
    // Test 3 – Replay attack
    // -------------------------------------------------------------------------

    /**
     * A Byzantine client re-submits the exact same signed transaction (same bytes,
     * same nonce) inside a new ClientRequest after the original was committed.  The
     * replay must be rejected because the sender's nonce has already advanced.
     */
    @Test
    void byzantineClient_replayAttack_isRejected() throws Exception {
        BlockchainClient client = createClientWithKey(0);
        Address sender = client.getEvmAddress();

        long nonce = currentNonce(sender);
        Transaction tx = Transaction.create(
                sender, freshAddress(), Wei.of(500), Bytes.EMPTY, 1L, 21_000L, nonce);

        // First submission – must succeed and advance the nonce.
        assertTrue(client.submitTransaction(tx), "Original transaction must commit");

        // Replay: wrap the same transaction bytes in a fresh ClientRequest.
        Transaction signedTx = tx.sign(keyManagers.get(0).getPrivateKey());
        String replayData = Base64.getEncoder().encodeToString(signedTx.serialize());

        boolean replayResult = client.submitRequest(replayData);

        assertFalse(replayResult,
                "Replaying a transaction with an already-used nonce must be rejected");
    }

    // -------------------------------------------------------------------------
    // Test 4 – Insufficient balance
    // -------------------------------------------------------------------------

    /**
     * A client whose account has no DepCoin balance attempts a transfer.
     * The node must reject it at the balance pre-check.
     */
    @Test
    void byzantineClient_insufficientBalance_isRejected() throws Exception {
        BlockchainClient broke = createClient(); // fresh key → zero balance

        Transaction tx = Transaction.create(
                broke.getEvmAddress(), freshAddress(), Wei.of(1_000), Bytes.EMPTY, 1L, 21_000L, 0L);

        boolean result = broke.submitTransaction(tx);

        assertFalse(result, "Transaction from a zero-balance account must be rejected");
    }

    // -------------------------------------------------------------------------
    // Test 5 – Double-spend prevention (pending-pool guard)
    // -------------------------------------------------------------------------

    /**
     * A client submits two transactions from the same address back-to-back before
     * the first one is committed.  The pending-pool guard (one tx per sender at a
     * time) ensures the second is rejected immediately, preventing double-spending.
     *
     * We verify the invariant: at most one of the two submissions can succeed.
     */
    @Test
    void byzantineClient_doublePend_onlyOneSucceeds() throws Exception {
        BlockchainClient client = createClientWithKey(1); // node 1 is funded
        Address sender = client.getEvmAddress();

        // Both transactions share nonce 0 – only the first one reaching the nodes
        // while the sender slot is free can be accepted.
        Transaction tx1 = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, 0L);
        Transaction tx2 = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, 0L);

        boolean r1 = client.submitTransaction(tx1);
        // At this point tx1 may still be in the pending pool or just committed.
        // Either way, tx2 with nonce 0 is invalid: nonce mismatch if tx1 committed,
        // or pending-sender conflict if tx1 is still queued.
        boolean r2 = client.submitTransaction(tx2);

        assertFalse(r1 && r2,
                "Both transactions with the same nonce cannot both succeed (double-spend prevented)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BlockchainClient createClient() throws Exception {
        int port = nextClientPort++;
        BlockchainClient client = new BlockchainClient(port, port);
        client.setNodePublicKeys(allNodePublicKeys());
        client.start();
        clientsToStop.add(client);
        return client;
    }

    /** Creates a client that uses node {@code nodeId}'s RSA key pair (genesis-funded address). */
    private BlockchainClient createClientWithKey(int nodeId) throws Exception {
        int port = nextClientPort++;
        KeyPair kp = new KeyPair(keyManagers.get(nodeId).getPublicKey(nodeId),
                                 keyManagers.get(nodeId).getPrivateKey());
        BlockchainClient client = new BlockchainClient(port, port, kp);
        client.setNodePublicKeys(allNodePublicKeys());
        client.start();
        clientsToStop.add(client);
        return client;
    }

    private Map<Integer, PublicKey> allNodePublicKeys() {
        Map<Integer, PublicKey> keys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            keys.put(i, keyManagers.get(i).getPublicKey(i));
        }
        return keys;
    }

    /** Returns the current on-chain nonce for {@code address} (accounts for genesis txs). */
    private long currentNonce(Address address) {
        return nodesToStop.get(0).getEvmService().getNonce(address);
    }

    private static int addrSeed = 0xB000;
    private static Address freshAddress() {
        byte[] bytes = new byte[20];
        int s = addrSeed++;
        bytes[18] = (byte) (s >> 8);
        bytes[19] = (byte) s;
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier cond, long ms)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(200);
        }
        return false;
    }
}
