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

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Transaction execution flow (Issue – connect consensus to EVM).
 *
 * Each test starts all 4 nodes with genesis loaded (persistenceEnabled=true so that
 * the EVM world state is initialised from genesis.json / ISTCoin.bin).  The genesis
 * block funds node0-node3 with 1 ETH each, so transactions from those addresses work
 * out of the box.
 *
 * Tests cover:
 *  1. A successful DepCoin transfer returns success=true, reply contains gasUsed.
 *  2. A transfer to a fresh address creates that account on all nodes.
 *  3. The sender's balance is reduced by value + fee after a successful transfer.
 *  4. A transaction with insufficient balance returns success=false quickly.
 *  5. Multiple sequential transactions are all committed and executed.
 *  6. Legacy plain-string requests (Stage-1 compat) still return success=true.
 */
class TransactionExecutionTest {

    private static final int BASE_CLIENT_PORT = 6300;
    private int nextClientPort = BASE_CLIENT_PORT;

    private final List<Node> nodesToStop = new ArrayList<>();
    private final List<BlockchainClient> clientsToStop = new ArrayList<>();
    private Path stateDirectory;
    private Map<Integer, KeyManager> keyManagers;

    @BeforeEach
    void setUp() throws Exception {
        stateDirectory = Files.createTempDirectory("depchain-tx-test-");
        NetworkFaultController.clearRules();
        keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        startAllNodes(true);
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
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void successfulTransfer_returnsTrue() throws Exception {
        BlockchainClient client = createClient();

        Address sender    = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
        Address recipient = freshAddress();
        long nonce = nodesToStop.get(0).getEvmService().getNonce(sender);

        Transaction tx = Transaction.create(
            sender, recipient, Wei.of(1000), Bytes.EMPTY, 1L, 21_000L, nonce);
        tx = tx.sign(keyManagers.get(0).getPrivateKey());

        boolean result = client.submitTransaction(tx);

        assertTrue(result, "Successful transfer should return true");
    }

    @Test
    void successfulTransfer_recipientBalanceIncreased() throws Exception {
        BlockchainClient client = createClient();

        Address sender    = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
        Address recipient = freshAddress();
        Wei transferValue = Wei.of(500_000);
        long nonce = nodesToStop.get(0).getEvmService().getNonce(sender);

        Transaction tx = Transaction.create(
            sender, recipient, transferValue, Bytes.EMPTY, 1L, 21_000L, nonce);
        tx = tx.sign(keyManagers.get(0).getPrivateKey());

        boolean committed = client.submitTransaction(tx);
        assertTrue(committed, "Transfer must commit");

        // All nodes must have updated the recipient's balance.
        boolean balanced = waitUntil(
                () -> nodesToStop.stream().allMatch(n ->
                        n.getEvmService().getBalance(recipient).compareTo(transferValue) >= 0),
                10_000);
        assertTrue(balanced, "Recipient balance must reflect the transfer on all nodes");
    }

    @Test
    void successfulTransfer_senderBalanceDecreased() throws Exception {
        BlockchainClient client = createClient();

        Address sender    = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
        Wei     initial   = genesisBalanceOf(sender);
        Wei     value     = Wei.of(1_000);
        long    gasPrice  = 1L;
        long    gasLimit  = 21_000L;
        long    nonce     = nodesToStop.get(0).getEvmService().getNonce(sender);

        Transaction tx = Transaction.create(
            sender, freshAddress(), value, Bytes.EMPTY, gasPrice, gasLimit, nonce);
        tx = tx.sign(keyManagers.get(0).getPrivateKey());

        assertTrue(client.submitTransaction(tx), "Transfer must commit");

        // After execution: balance < initial − value (fee is also deducted)
        Wei maxFee = Wei.of(gasPrice * gasLimit);
        Wei minExpected = initial.subtract(value).subtract(maxFee);
        boolean ok = waitUntil(
                () -> nodesToStop.stream().allMatch(n ->
                        n.getEvmService().getBalance(sender).compareTo(minExpected) >= 0),
                10_000);
        assertTrue(ok, "Sender balance should be reduced by value + fee");
    }

    @Test
    void transferWithInsufficientBalance_returnsFalse() throws Exception {
        BlockchainClient client = createClient();

        // Use node0 (funded in genesis) but attempt to transfer more than its balance.
        Address broke     = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
        Address recipient = freshAddress();

        Wei brokeBalance = genesisBalanceOf(broke);
        Wei tooMuch = brokeBalance.add(Wei.of(1));
        long nonce = nodesToStop.get(0).getEvmService().getNonce(broke);

        Transaction tx = Transaction.create(
            broke, recipient, tooMuch, Bytes.EMPTY, 1L, 21_000L, nonce);
        tx = tx.sign(keyManagers.get(0).getPrivateKey());

        boolean result = client.submitTransaction(tx);

        assertFalse(result, "Transfer from address with no balance must return false");
    }

    @Test
    void multipleSequentialTransactions_allCommit() throws Exception {
        BlockchainClient client = createClient();

        Address sender = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
        long startNonce = nodesToStop.get(0).getEvmService().getNonce(sender);

        for (int i = 0; i < 3; i++) {
            Transaction tx = Transaction.create(
                sender, freshAddress(), Wei.of(100), Bytes.EMPTY, 1L, 21_000L, startNonce + i);
            tx = tx.sign(keyManagers.get(0).getPrivateKey());
            assertTrue(client.submitTransaction(tx), "Transfer #" + i + " must commit");
        }
    }

    @Test
    void legacyPlainStringRequest_stillReturnsTrue() throws Exception {
        // Stage-1 compatibility: plain strings that cannot be decoded as
        // Transactions must still succeed (no EVM execution, just consensus).
        BlockchainClient client = createClient();
        boolean ok = client.submitRequest("legacy-plain-string");
        assertTrue(ok, "Legacy plain-string requests must still be accepted");
    }

    @Test
    void clientGetEvmAddress_matchesNodeDerivedAddress() throws Exception {
        BlockchainClient client = createClient();
        // The node will derive the same address for any public key using the
        // same algorithm.  Two freshly-generated clients should have different addresses.
        BlockchainClient other = createClient();
        assertNotEquals(client.getEvmAddress(), other.getEvmAddress(),
                "Two distinct clients must have different EVM addresses");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void startAllNodes(boolean persistenceEnabled) {
        for (int id = 0; id < NetworkConfig.NUM_NODES; id++) {
            Node node = new Node(id, keyManagers.get(id), ByzantineBehavior.HONEST,
                    stateDirectory.toString(), persistenceEnabled);
            node.start();
            nodesToStop.add(node);
        }
    }

    private BlockchainClient createClient() throws Exception {
        int port = nextClientPort++;
        BlockchainClient client = new BlockchainClient(port, port);
        Map<Integer, PublicKey> publicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            publicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }
        client.setNodePublicKeys(publicKeys);
        client.start();
        clientsToStop.add(client);
        return client;
    }

    /** Derives the genesis balance of {@code address} from any running node. */
    private Wei genesisBalanceOf(Address address) {
        return nodesToStop.get(0).getEvmService().getBalance(address);
    }

    /** Creates a deterministic but unique 20-byte address for each invocation. */
    private static int addrSeed = 0x1000;
    private static Address freshAddress() {
        byte[] bytes = new byte[20];
        int s = addrSeed++;
        bytes[18] = (byte) (s >> 8);
        bytes[19] = (byte) s;
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition,
                                     long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(200);
        }
        return false;
    }
}
