package depchain;

import depchain.blockchain.EvmService;
import depchain.blockchain.Transaction;
import depchain.client.ClientReply;
import depchain.client.ClientRequest;
import depchain.config.NetworkConfig;
import depchain.crypto.CryptoUtils;
import depchain.net.FairLossLinks.NodeAddress;
import depchain.net.Message;
import depchain.net.MessageType;
import depchain.net.fault.NetworkFaultController;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import depchain.crypto.KeyManager;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClientReplyOutputTest {

    private static final String SEL_BALANCE_OF = "70a08231"; // balanceOf(address)

    private static final int CLIENT_ID = 9200;
    private static final int CLIENT_REPLY_PORT = 6720;

    private final List<Node> nodesToStop = new ArrayList<>();
    private Path stateDirectory;
    private Map<Integer, KeyManager> keyManagers;

    @BeforeEach
    void setUp() throws Exception {
        stateDirectory = Files.createTempDirectory("depchain-reply-output-test-");
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
    void tearDown() {
        for (Node node : nodesToStop) {
            try { node.stop(); } catch (Exception ignored) {}
        }
        nodesToStop.clear();
        NetworkFaultController.clearRules();
    }

    @Test
    void clientReply_includesOutput_forBalanceOfCall() throws Exception {
        Address istCoin = waitForIstCoinAddress();
        assertNotNull(istCoin, "ISTCoin contract must be deployed by genesis");

        // Use node0's keypair so that the derived EVM address is genesis-funded.
        PublicKey pub = keyManagers.get(0).getPublicKey(0);
        PrivateKey priv = keyManagers.get(0).getPrivateKey();
        KeyPair keyPair = new KeyPair(pub, priv);
        Address sender = EvmService.deriveAddress(pub);

        long nonce = nodesToStop.get(0).getEvmService().getNonce(sender);

        Bytes calldata = Bytes.fromHexString(SEL_BALANCE_OF + addrArg(sender));
        Bytes expectedOut = nodesToStop.get(0).getEvmService().callContract(sender, istCoin, calldata, 100_000L);
        BigInteger expectedBalance = expectedOut.isEmpty()
                ? BigInteger.ZERO
                : new BigInteger(1, expectedOut.toArray());
        assertTrue(expectedBalance.compareTo(BigInteger.ZERO) > 0,
                "Sanity: genesis must mint some IST to deployer");

        Transaction tx = Transaction.create(
                sender,
                istCoin,
                Wei.ZERO,
                calldata,
                1L,
                150_000L,
                nonce
        ).sign(priv);

        String data = Base64.getEncoder().encodeToString(tx.serialize());
        ClientRequest request = buildSignedClientRequest(keyPair, data);

        int requiredReplies = NetworkConfig.MAX_FAULTS + 1;
        Map<String, Set<Integer>> respondersByMessage = new HashMap<>();

        try (DatagramSocket replySocket = new DatagramSocket(CLIENT_REPLY_PORT)) {
            replySocket.setSoTimeout(12_000);

            sendRequestToAllNodes(request);

            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline) {
                ClientReply reply = receiveValidReply(replySocket, request.getRequestId());
                if (reply == null) {
                    continue;
                }
                assertTrue(reply.isSuccess(), "balanceOf call should succeed");

                String msg = reply.getMessage();
                assertNotNull(msg);
                assertTrue(msg.contains("output="), "Reply message must include output=...");

                String outHex = parseField(msg, "output");
                assertNotNull(outHex, "output field must be present");

                Bytes out = Bytes.fromHexString(outHex);
                BigInteger balance = out.isEmpty() ? BigInteger.ZERO : new BigInteger(1, out.toArray());
                assertEquals(expectedBalance, balance, "Returned output must match EVM balanceOf result");

                respondersByMessage.computeIfAbsent(msg, ignored -> new HashSet<>())
                        .add(reply.getResponderNodeId());

                if (respondersByMessage.get(msg).size() >= requiredReplies) {
                    return;
                }
            }
        }

        fail("Did not receive f+1 identical replies with output within timeout");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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

    private ClientRequest buildSignedClientRequest(KeyPair keyPair, String data) {
        String requestId = UUID.randomUUID().toString();
        ClientRequest unsignedRequest = new ClientRequest(
                CLIENT_ID,
                requestId,
                data,
                System.currentTimeMillis(),
                "localhost",
                CLIENT_REPLY_PORT,
                keyPair.getPublic().getEncoded(),
                null
        );
        byte[] signature = CryptoUtils.sign(unsignedRequest.bytesToSign(), keyPair.getPrivate());
        return unsignedRequest.withSignature(signature);
    }

    private void sendRequestToAllNodes(ClientRequest request) throws Exception {
        Map<Integer, NodeAddress> addrs = NetworkConfig.getAllNodeAddresses();
        byte[] payload = request.serialize();

        for (int nodeId = 0; nodeId < NetworkConfig.NUM_NODES; nodeId++) {
            NodeAddress addr = addrs.get(nodeId);
            Message msg = new Message(
                    CLIENT_ID,
                    nodeId,
                    System.nanoTime(),
                    MessageType.CLIENT_REQUEST,
                    payload
            );
            byte[] bytes = msg.serialize();

            try (DatagramSocket socket = new DatagramSocket()) {
                DatagramPacket packet = new DatagramPacket(
                        bytes,
                        bytes.length,
                        InetAddress.getByName(addr.host),
                        addr.port
                );
                socket.send(packet);
            }
        }
    }

    private ClientReply receiveValidReply(DatagramSocket socket, String requestId) {
        try {
            byte[] buf = new byte[65536];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

            Message message = Message.deserialize(data);
            if (message.getType() != MessageType.CLIENT_REPLY) {
                return null;
            }

            int senderId = message.getSenderId();
            PublicKey nodePk = keyManagers.get(senderId).getPublicKey(senderId);
            if (message.getSignature() == null || message.getSignature().length == 0) {
                return null;
            }
            if (!CryptoUtils.verify(message.getBytesToSign(), message.getSignature(), nodePk)) {
                return null;
            }

            ClientReply reply = ClientReply.deserialize(message.getPayload());
            if (!requestId.equals(reply.getRequestId())) {
                return null;
            }
            if (senderId != reply.getResponderNodeId()) {
                return null;
            }
            return reply;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String addrArg(Address address) {
        return "000000000000000000000000" + address.toUnprefixedHexString();
    }

    private static String parseField(String msg, String key) {
        // msg is comma-separated key=value pairs.
        String[] parts = msg.split(",");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String k = part.substring(0, idx).trim();
            String v = part.substring(idx + 1).trim();
            if (key.equals(k)) {
                return v;
            }
        }
        return null;
    }
}
