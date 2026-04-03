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
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test: DepCoin transfer + ERC-20 ISTCoin call (Issue #17).
 *
 * Verifies that a single client can atomically exercise both layers of the
 * DepChain system in sequence:
 *
 *  1. A native DepCoin transfer reduces the sender's DepCoin balance and
 *     credits the recipient's DepCoin balance.
 *  2. A subsequent ISTCoin ERC-20 {@code transfer()} call moves IST tokens
 *     from the deployer (node 0) to a recipient address.
 *
 * Both operations flow through full BFT consensus and EVM execution.
 */
class EndToEndTest {

    // ERC-20 function selectors
    private static final String SEL_BALANCE_OF = "70a08231"; // balanceOf(address)
    private static final String SEL_TRANSFER   = "a9059cbb"; // transfer(address,uint256)

    private static final int BASE_CLIENT_PORT = 6500;
    private int nextClientPort = BASE_CLIENT_PORT;

    private final List<Node> nodesToStop = new ArrayList<>();
    private final List<BlockchainClient> clientsToStop = new ArrayList<>();
    private Path stateDirectory;
    private Map<Integer, KeyManager> keyManagers;

    @BeforeEach
    void setUp() throws Exception {
        stateDirectory = Files.createTempDirectory("depchain-e2e-test-");
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
    // Combined test
    // -------------------------------------------------------------------------

    /**
     * Full end-to-end scenario:
     *
     * <ol>
     *   <li>Node 0's client sends 10 000 Wei DepCoin to a fresh address.</li>
     *   <li>The same client calls ISTCoin.transfer() to move 1 000 IST tokens
     *       to a different address.</li>
     *   <li>Assertions verify both the DepCoin balance update and the ERC-20
     *       token balance update are reflected on every node.</li>
     * </ol>
     */
    @Test
    void depcoinTransferAndErc20CallBothSucceed() throws Exception {
        // Client acts as node 0 (genesis-funded for both DepCoin and ISTCoin).
        BlockchainClient client = createClientWithKey(0);
        Address sender = client.getEvmAddress();

        Address depcoinRecipient = freshAddress();
        Address istRecipient     = freshAddress();

        Wei depcoinAmount = Wei.of(10_000L);
        long istAmount    = 100_00L; // 1 000.00 IST (2 decimals)

        // Fetch post-genesis nonce (node0 is the ISTCoin deployer so its nonce starts at 1).
        long nonce = nodesToStop.get(0).getEvmService().getNonce(sender);

        // Snapshot balances before.
        Wei initialSenderDepCoin = nodesToStop.get(0).getEvmService().getBalance(sender);

        // ── Step 1: DepCoin transfer ──────────────────────────────────────────
        Transaction depcoinTx = Transaction.create(
                sender, depcoinRecipient, depcoinAmount, Bytes.EMPTY, 1L, 21_000L, nonce);
        boolean depcoinOk = client.submitTransaction(depcoinTx);
        assertTrue(depcoinOk, "DepCoin transfer must succeed");

        // ── Step 2: ISTCoin ERC-20 transfer ──────────────────────────────────
        Address istCoin = waitForIstCoinAddress();
        assertNotNull(istCoin, "ISTCoin contract must be deployed by genesis");

        Bytes calldata = Bytes.fromHexString(SEL_TRANSFER + addrArg(istRecipient) + uint256Arg(istAmount));
        Transaction erc20Tx = Transaction.create(
                sender, istCoin, Wei.ZERO, calldata, 1L, 200_000L, nonce + 1);
        boolean erc20Ok = client.submitTransaction(erc20Tx);
        assertTrue(erc20Ok, "ISTCoin transfer() call must succeed");

        // ── Assertions ───────────────────────────────────────────────────────

        // DepCoin: recipient received the amount.
        boolean depcoinRecipientFunded = waitUntil(
                () -> nodesToStop.stream().allMatch(n ->
                        n.getEvmService().getBalance(depcoinRecipient)
                                .compareTo(depcoinAmount) >= 0),
                12_000);
        assertTrue(depcoinRecipientFunded, "DepCoin recipient balance must equal the transferred amount");

        // DepCoin: sender balance decreased by at least the transfer amount.
        boolean senderDebited = waitUntil(
                () -> nodesToStop.stream().allMatch(n ->
                        n.getEvmService().getBalance(sender)
                                .compareTo(initialSenderDepCoin.subtract(depcoinAmount)) <= 0),
                12_000);
        assertTrue(senderDebited, "Sender DepCoin balance must be reduced by at least the transfer value");

        // ISTCoin: recipient holds the transferred tokens on every node.
        boolean istRecipientFunded = waitUntil(
                () -> nodesToStop.stream().allMatch(n -> {
                    BigInteger bal = istBalanceOf(n.getEvmService(), istCoin, istRecipient);
                    return bal.compareTo(BigInteger.valueOf(istAmount)) >= 0;
                }),
                12_000);
        assertTrue(istRecipientFunded,
                "IST recipient token balance must equal the transferred amount on all nodes");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Waits until the genesis ISTCoin contract address is known by at least one node. */
    private Address waitForIstCoinAddress() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (Node n : nodesToStop) {
                Address addr = n.getIstCoinAddress();
                if (addr != null) return addr;
            }
            Thread.sleep(200);
        }
        return null;
    }

    /** Calls {@code balanceOf(account)} on the ISTCoin contract. */
    private static BigInteger istBalanceOf(EvmService evm, Address contract, Address account) {
        Bytes calldata = Bytes.fromHexString(SEL_BALANCE_OF + addrArg(account));
        Bytes result = evm.callContract(account, contract, calldata, 100_000L);
        return result.isEmpty() ? BigInteger.ZERO : new BigInteger(1, result.toArray());
    }

    private BlockchainClient createClientWithKey(int nodeId) throws Exception {
        int port = nextClientPort++;
        KeyPair kp = new KeyPair(keyManagers.get(nodeId).getPublicKey(nodeId),
                                 keyManagers.get(nodeId).getPrivateKey());
        BlockchainClient client = new BlockchainClient(port, port, kp);
        Map<Integer, PublicKey> publicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            publicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }
        client.setNodePublicKeys(publicKeys);
        client.start();
        clientsToStop.add(client);
        return client;
    }

    private static int addrSeed = 0xE000;
    private static Address freshAddress() {
        byte[] bytes = new byte[20];
        int s = addrSeed++;
        bytes[18] = (byte) (s >> 8);
        bytes[19] = (byte) s;
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }

    private static String addrArg(Address address) {
        return "000000000000000000000000" + address.toUnprefixedHexString();
    }

    private static String uint256Arg(long value) {
        return String.format("%064x", value);
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
