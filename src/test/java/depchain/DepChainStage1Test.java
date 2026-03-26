package depchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.client.BlockchainClient;
import depchain.client.ClientReply;
import depchain.client.ClientRequest;
import depchain.config.NetworkConfig;
import depchain.consensus.ConsensusPhase;
import depchain.consensus.ConsensusVote;
import depchain.consensus.QuorumCertificate;
import depchain.crypto.KeyManager;
import depchain.net.Message;
import depchain.net.MessageType;
import depchain.net.fault.NetworkFaultController;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DepChainStage1Test {

    private final List<Node> nodesToStop = new ArrayList<>();
    private final List<BlockchainClient> clientsToStop = new ArrayList<>();
    private Path stateDirectory;

    @BeforeEach
    void setUp() throws Exception {
        stateDirectory = Files.createTempDirectory("depchain-state-test-");
        NetworkFaultController.clearRules();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (BlockchainClient client : clientsToStop) {
            try {
                client.stop();
            } catch (Exception ignored) {}
        }
        clientsToStop.clear();

        for (Node node : nodesToStop) {
            try {
                node.stop();
            } catch (Exception ignored) {}
        }
        nodesToStop.clear();
        NetworkFaultController.clearRules();

        if (stateDirectory != null && Files.exists(stateDirectory)) {
            try (
                java.util.stream.Stream<Path> paths = Files.walk(stateDirectory)
            ) {
                paths
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {}
                    });
            }
        }
    }

    @Test
    void consensusAppendsToAllNodes() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        startNodes(List.of(0, 1, 2, 3), keyManagers);

        BlockchainClient client = createClient(200, 6200, keyManagers);
        boolean ok = client.submitRequest("test-consensus-1");
        assertTrue(ok, "Client request should be confirmed");

        boolean replicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-consensus-1")
                    ),
            12000
        );
        assertTrue(
            replicated,
            "All nodes should eventually append the decided value"
        );
    }

    @Test
    void timeoutViewChangeSurvivesLeaderCrash() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );

        startNodes(List.of(1, 2, 3), keyManagers);

        BlockchainClient client = createClient(201, 6201, keyManagers);
        boolean ok = client.submitRequest("test-failover-1");
        assertTrue(ok, "Client request should succeed after view change");

        boolean replicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-failover-1")
                    ),
            20000
        );
        assertTrue(
            replicated,
            "Remaining nodes should commit after timeout-based leader change"
        );
    }

    @Test
    void invalidNodeSignatureIsRejected() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        Node node = new Node(
            2,
            keyManagers.get(2),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        CountDownLatch delivered = new CountDownLatch(1);
        node.setListener(
            new Node.NodeListener() {
                @Override
                public void onMessageReceived(int senderId, Message message) {
                    if (message.getType() == MessageType.DATA) {
                        delivered.countDown();
                    }
                }

                @Override
                public void onBlockAppended(String data) {

                }
            }
        );
        node.start();
        nodesToStop.add(node);

        Thread.sleep(500);

        Message forged = new Message(1, 2, 9999L, MessageType.DATA, "forged");
        forged.setSignature(new byte[] { 1, 2, 3, 4, 5, 6 });
        byte[] data = forged.serialize();

        try (DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName("localhost"),
                NetworkConfig.getNodePort(2)
            );
            socket.send(packet);
        }

        boolean wronglyDelivered = delivered.await(2, TimeUnit.SECONDS);
        assertFalse(wronglyDelivered, "Node should drop forged signed message");
    }

    @Test
    void faultInjectionDropDelayDuplicateCorruptStillCommits()
        throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );

        NetworkFaultController.addDropRule(0, 1, MessageType.PREPARE, 8);
        NetworkFaultController.addDelayRule(
            0,
            2,
            MessageType.PRE_COMMIT,
            300,
            10
        );
        NetworkFaultController.addDuplicateRule(0, 3, MessageType.COMMIT, 2, 8);
        NetworkFaultController.addCorruptRule(0, 2, MessageType.PREPARE, 6);

        startNodes(List.of(0, 1, 2, 3), keyManagers);
        BlockchainClient client = createClient(202, 6202, keyManagers);

        boolean ok = client.submitRequest("test-faults-1");
        assertTrue(ok, "Consensus should survive injected network faults");

        boolean replicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n -> n.getBlockchain().contains("test-faults-1")),
            22000
        );
        assertTrue(replicated, "All nodes should converge after fault burst");

        boolean noDuplicateDecision = nodesToStop
            .stream()
            .allMatch(
                n ->
                    n
                        .getBlockchain()
                        .stream()
                        .filter(v -> "test-faults-1".equals(v))
                        .count() ==
                    1
            );
        assertTrue(
            noDuplicateDecision,
            "Each request must be executed at most once"
        );
    }

    @Test
    void byzantineEquivocatingLeaderStillMakesProgress() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );

        Map<Integer, ByzantineBehavior> behaviorById = new HashMap<>();
        behaviorById.put(0, ByzantineBehavior.EQUIVOCATE_LEADER);
        startNodes(List.of(0, 1, 2, 3), keyManagers, behaviorById, false);

        BlockchainClient client = createClient(203, 6203, keyManagers);
        boolean ok = client.submitRequest("test-byz-equiv-1");
        assertTrue(
            ok,
            "Request should eventually commit after view change to an honest leader"
        );

        boolean replicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-byz-equiv-1")
                    ),
            30000
        );
        assertTrue(
            replicated,
            "Cluster should still commit the request under equivocation"
        );
    }

    @Test
    void invalidVoteSignatureByzantineNodeDoesNotBreakConsensus()
        throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        Map<Integer, ByzantineBehavior> behaviorById = new HashMap<>();
        behaviorById.put(3, ByzantineBehavior.INVALID_VOTE_SIGNATURE);
        startNodes(List.of(0, 1, 2, 3), keyManagers, behaviorById, false);

        BlockchainClient client = createClient(204, 6204, keyManagers);
        boolean ok = client.submitRequest("test-invalid-vote-byz-1");
        assertTrue(ok, "Consensus should tolerate one Byzantine invalid voter");

        boolean replicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-invalid-vote-byz-1")
                    ),
            18000
        );
        assertTrue(replicated, "Valid quorum should still decide");
    }

    @Test
    void tamperedQcSignatureIsRejected() {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        List<ConsensusVote> votes = List.of(
            ConsensusVote.create(
                ConsensusPhase.PREPARE,
                4,
                "qc-block-1",
                0,
                keyManagers.get(0)
            ),
            ConsensusVote.create(
                ConsensusPhase.PREPARE,
                4,
                "qc-block-1",
                1,
                keyManagers.get(1)
            ),
            ConsensusVote.create(
                ConsensusPhase.PREPARE,
                4,
                "qc-block-1",
                2,
                keyManagers.get(2)
            )
        );

        QuorumCertificate qc = QuorumCertificate.fromVotes(
            ConsensusPhase.PREPARE,
            4,
            "qc-block-1",
            0,
            keyManagers.get(0),
            votes
        );
        assertTrue(qc.verify(keyManagers.get(3)), "Fresh QC should verify");

        byte[] tamperedSignature = qc.getQcSignature();
        tamperedSignature[0] = (byte) (tamperedSignature[0] ^ 0x5A);

        QuorumCertificate tamperedQc = new QuorumCertificate(
            qc.getPhase(),
            qc.getView(),
            qc.getBlockHash(),
            qc.getCreatorId(),
            qc.getSignatures(),
            tamperedSignature
        );

        assertFalse(
            tamperedQc.verify(keyManagers.get(3)),
            "Nodes should reject a QC with a bad leader signature"
        );
    }

    @Test
    void clientRequiresDistinctSignedReplies() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        BlockchainClient client = createClient(206, 6206, keyManagers);

        Method buildSignedRequest = BlockchainClient.class.getDeclaredMethod(
            "buildSignedRequest",
            String.class
        );
        buildSignedRequest.setAccessible(true);
        ClientRequest request = (ClientRequest) buildSignedRequest.invoke(
            client,
            "reply-quorum-test"
        );

        Method waitForReply = BlockchainClient.class.getDeclaredMethod(
            "waitForReply",
            String.class
        );
        waitForReply.setAccessible(true);

        Thread senderThread = new Thread(() -> {
            try {
                Thread.sleep(150);
                sendSignedClientReply(
                    keyManagers.get(0),
                    0,
                    client.getClientId(),
                    6206,
                    request.getRequestId(),
                    1L,
                    true,
                    "ok-from-0",
                    7
                );
                Thread.sleep(150);
                sendSignedClientReply(
                    keyManagers.get(0),
                    0,
                    client.getClientId(),
                    6206,
                    request.getRequestId(),
                    2L,
                    true,
                    "duplicate-from-0",
                    7
                );
                Thread.sleep(700);
                sendSignedClientReply(
                    keyManagers.get(1),
                    1,
                    client.getClientId(),
                    6206,
                    request.getRequestId(),
                    3L,
                    true,
                    "ok-from-1",
                    7
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "client-reply-sender");
        senderThread.setDaemon(true);
        senderThread.start();

        long startedAt = System.currentTimeMillis();
        boolean ok = (boolean) waitForReply.invoke(client, request.getRequestId());
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertTrue(ok, "Client should accept two distinct signed replies");
        assertTrue(
            elapsedMs >= 800,
            "Client should not count duplicate replies from the same node"
        );
    }

    @Test
    void nodeStatePersistsAcrossRestart() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        startNodes(List.of(0, 1, 2, 3), keyManagers, new HashMap<>(), true);

        BlockchainClient client = createClient(205, 6205, keyManagers);
        boolean first = client.submitRequest("test-persist-1");
        assertTrue(first, "First request should commit");

        boolean firstReplicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-persist-1")
                    ),
            15000
        );
        assertTrue(firstReplicated, "Initial value should replicate");

        Node node3 = nodesToStop
            .stream()
            .filter(n -> n.getNodeId() == 3)
            .findFirst()
            .orElseThrow();
        node3.stop();
        nodesToStop.remove(node3);

        Node restarted = new Node(
            3,
            keyManagers.get(3),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        restarted.start();
        nodesToStop.add(restarted);

        boolean restored = waitUntil(
            () -> restarted.getBlockchain().contains("test-persist-1"),
            5000
        );
        assertTrue(restored, "Restarted node should reload persisted chain");
        assertTrue(
            restarted.getAPL().getCurrentMessageId() > 0,
            "Restarted node should restore its outgoing sequence number"
        );

        boolean second = client.submitRequest("test-persist-2");
        assertTrue(second, "Second request should still commit after restart");

        boolean secondReplicated = waitUntil(
            () ->
                nodesToStop
                    .stream()
                    .allMatch(n ->
                        n.getBlockchain().contains("test-persist-2")
                    ),
            18000
        );
        assertTrue(
            secondReplicated,
            "All nodes should replicate post-restart value"
        );
    }

    @Test
    void replayWatermarkPersistsAcrossRestart() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        AtomicInteger totalDelivered = new AtomicInteger(0);

        Node node = new Node(
            1,
            keyManagers.get(1),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        node.setListener(
            new Node.NodeListener() {
                @Override
                public void onMessageReceived(int senderId, Message message) {
                    if (message.getType() == MessageType.DATA) {
                        totalDelivered.incrementAndGet();
                    }
                }

                @Override
                public void onBlockAppended(String data) {}
            }
        );
        node.start();
        nodesToStop.add(node);
        Thread.sleep(500);

        Message first = new Message(0, 1, 100L, MessageType.DATA, "first");
        first.setSignature(keyManagers.get(0).computeMac(1, first.getBytesToSign()));
        sendRawUdp(first.serialize(), NetworkConfig.getNodePort(1));
        assertTrue(
            waitUntil(() -> totalDelivered.get() == 1, 3000),
            "Initial signed message should be delivered"
        );

        node.stop();
        nodesToStop.remove(node);

        Node restarted = new Node(
            1,
            keyManagers.get(1),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        restarted.setListener(
            new Node.NodeListener() {
                @Override
                public void onMessageReceived(int senderId, Message message) {
                    if (message.getType() == MessageType.DATA) {
                        totalDelivered.incrementAndGet();
                    }
                }

                @Override
                public void onBlockAppended(String data) {}
            }
        );
        restarted.start();
        nodesToStop.add(restarted);
        Thread.sleep(500);

        Message replay = new Message(0, 1, 100L, MessageType.DATA, "replay");
        replay.setSignature(keyManagers.get(0).computeMac(1, replay.getBytesToSign()));
        sendRawUdp(replay.serialize(), NetworkConfig.getNodePort(1));

        Thread.sleep(1500);
        assertEquals(
            1,
            totalDelivered.get(),
            "Restarted node should keep replay watermarks and reject old messages"
        );
    }

    @Test
    void replayAttackIsRejected() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        Node node = new Node(
            2,
            keyManagers.get(2),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        CountDownLatch firstDelivered = new CountDownLatch(1);
        AtomicInteger totalDelivered = new AtomicInteger(0);

        node.setListener(
            new Node.NodeListener() {
                @Override
                public void onMessageReceived(int senderId, Message message) {
                    if (message.getType() == MessageType.DATA) {
                        totalDelivered.incrementAndGet();
                        firstDelivered.countDown();
                    }
                }

                @Override
                public void onBlockAppended(String data) {}
            }
        );
        node.start();
        nodesToStop.add(node);
        Thread.sleep(500);

        KeyManager senderKeys = keyManagers.get(1);

        // Send a valid signed message with a high sequence number
        long highMsgId = 10000L;
        Message firstMsg = new Message(1, 2, highMsgId, MessageType.DATA, "first");
        firstMsg.setSignature(senderKeys.computeMac(2, firstMsg.getBytesToSign()));
        sendRawUdp(firstMsg.serialize(), NetworkConfig.getNodePort(2));

        assertTrue(
            firstDelivered.await(3, TimeUnit.SECONDS),
            "First message should be delivered"
        );
        assertEquals(1, totalDelivered.get(), "Exactly one message should be delivered");

        // Send a valid signed message with an older sequence number (replay scenario)
        long oldMsgId = 1L;
        Message replayMsg = new Message(1, 2, oldMsgId, MessageType.DATA, "replay");
        replayMsg.setSignature(senderKeys.computeMac(2, replayMsg.getBytesToSign()));
        sendRawUdp(replayMsg.serialize(), NetworkConfig.getNodePort(2));

        Thread.sleep(1500);
        assertEquals(
            1,
            totalDelivered.get(),
            "APL should reject message with old sequence number (replay attack)"
        );
    }

    @Test
    void invalidAckSignatureDoesNotStopRetransmission() throws Exception {
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        Node node = new Node(
            0,
            keyManagers.get(0),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            true
        );
        node.start();
        nodesToStop.add(node);
        Thread.sleep(500);

        node.send(1, MessageType.DATA, "needs-ack");
        assertTrue(
            waitUntil(
                () ->
                    node
                        .getAPL()
                        .getPerfectLinks()
                        .getStubbornLinks()
                        .getPendingMessageCount() == 1,
                2000
            ),
            "Message should stay pending while no valid ACK arrives"
        );

        long originalMessageId = node.getAPL().getCurrentMessageId();
        Message invalidAck = new Message(
            1,
            0,
            9000L,
            MessageType.ACK,
            "0-" + originalMessageId
        );
        invalidAck.setSignature(new byte[] { 1, 2, 3 });
        sendRawUdp(invalidAck.serialize(), NetworkConfig.getNodePort(0));

        Thread.sleep(800);
        assertEquals(
            1,
            node
                .getAPL()
                .getPerfectLinks()
                .getStubbornLinks()
                .getPendingMessageCount(),
            "Bad ACK signature must not stop retransmission"
        );

        Message validAck = new Message(
            1,
            0,
            9001L,
            MessageType.ACK,
            "0-" + originalMessageId
        );
        validAck.setSignature(keyManagers.get(1).sign(validAck.getBytesToSign()));
        sendRawUdp(validAck.serialize(), NetworkConfig.getNodePort(0));

        assertTrue(
            waitUntil(
                () ->
                    node
                        .getAPL()
                        .getPerfectLinks()
                        .getStubbornLinks()
                        .getPendingMessageCount() == 0,
                3000
            ),
            "Valid signed ACK should stop retransmission"
        );
    }

    private void sendRawUdp(byte[] data, int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName("localhost"),
                port
            );
            socket.send(packet);
        }
    }

    private void sendSignedClientReply(
        KeyManager keyManager,
        int senderNodeId,
        int clientId,
        int clientPort,
        String requestId,
        long messageId,
        boolean success,
        String status,
        int view
    ) throws Exception {
        ClientReply reply = new ClientReply(
            requestId,
            success,
            status,
            senderNodeId,
            view
        );
        Message message = new Message(
            senderNodeId,
            clientId,
            messageId,
            MessageType.CLIENT_REPLY,
            reply.serialize()
        );
        message.setSignature(keyManager.sign(message.getBytesToSign()));
        sendRawUdp(message.serialize(), clientPort);
    }

    private void startNodes(
        List<Integer> ids,
        Map<Integer, KeyManager> keyManagers
    ) {
        startNodes(ids, keyManagers, new HashMap<>(), false);
    }

    private void startNodes(
        List<Integer> ids,
        Map<Integer, KeyManager> keyManagers,
        Map<Integer, ByzantineBehavior> behaviorById,
        boolean persistenceEnabled
    ) {
        for (int id : ids) {
            ByzantineBehavior behavior = behaviorById.getOrDefault(
                id,
                ByzantineBehavior.HONEST
            );
            Node node = new Node(
                id,
                keyManagers.get(id),
                behavior,
                stateDirectory.toString(),
                persistenceEnabled
            );
            node.start();
            nodesToStop.add(node);
        }
    }

    private BlockchainClient createClient(
        int clientId,
        int port,
        Map<Integer, KeyManager> keyManagers
    ) throws Exception {
        BlockchainClient client = new BlockchainClient(clientId, port);
        Map<Integer, PublicKey> publicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            publicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }
        client.setNodePublicKeys(publicKeys);
        client.start();
        clientsToStop.add(client);
        return client;
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
