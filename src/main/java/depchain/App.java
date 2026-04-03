package depchain;

import depchain.blockchain.EvmService;
import depchain.blockchain.Transaction;
import depchain.client.BlockchainClient;
import depchain.config.NetworkConfig;
import depchain.crypto.CryptoUtils;
import depchain.crypto.KeyManager;
import depchain.net.MessageType;
import depchain.net.fault.NetworkFaultController;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public class App {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];

        try {
            switch (command) {
                case "genkeys":
                    generateKeys();
                    break;
                case "node":
                    if (args.length < 2) {
                        System.err.println("need a node id here");
                        printUsage();
                        return;
                    }
                    int nodeId = Integer.parseInt(args[1]);
                    ByzantineBehavior behavior = ByzantineBehavior.HONEST;
                    if (args.length >= 3) {
                        behavior = ByzantineBehavior.valueOf(
                            args[2].toUpperCase()
                        );
                    }
                    startNode(nodeId, behavior);
                    break;
                case "client":
                    if (args.length < 2) {
                        System.err.println("need a client id here");
                        printUsage();
                        return;
                    }
                    int clientId = Integer.parseInt(args[1]);
                    startClient(clientId);
                    break;
                case "test":
                    runDemoTest();
                    break;
                case "demo_faults":
                    runFaultInjectionDemo();
                    break;
                case "demo_byz":
                    runByzantineDemo();
                    break;
                case "demo_persist":
                    runPersistenceDemo();
                    break;
                case "demo_stage2_transfer":
                    runStage2DepCoinTransferDemo();
                    break;
                case "demo_stage2_erc20":
                    runStage2Erc20TransferDemo();
                    break;
                case "demo_stage2_byzclient":
                    runStage2ByzantineClientDemo();
                    break;
                case "demo_stage2_6clients":
                    runStage2SixClientsDemo();
                    break;
                case "config":
                    NetworkConfig.printConfig();
                    break;
                default:
                    System.err.println("dont know this cmd: " + command);
                    printUsage();
            }
        } catch (Exception e) {
            System.err.println("something broke a bit: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("DepChain thing");
        System.out.println();
        System.out.println("try one of these:");
        System.out.println(
            "  mvn exec:java -Dexec.args=\"genkeys\"         - make keys for nodes and static clients"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"node <id> [behavior]\" - start a node (HONEST|SILENT|EQUIVOCATE_LEADER|INVALID_VOTE_SIGNATURE)"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"client <id>\"     - start a static client (id: 0.." + (NetworkConfig.NUM_STATIC_CLIENTS - 1) + ")"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_stage2_transfer\"   - Stage 2 demo: DepCoin transfer through consensus"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_stage2_erc20\"      - Stage 2 demo: ERC-20 transfer through consensus"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_stage2_byzclient\"  - Stage 2 demo: byzantine client spoof rejected"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_stage2_6clients\"    - Stage 2 demo: 6 static clients submit txs (requires genkeys)"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_faults\"     - Stage 1/infra demo: fault injection"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_byz\"        - Stage 1/infra demo: byzantine leader equivocation"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_persist\"    - Stage 1/infra demo: persistence/restart"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"config\"          - show net config"
        );
        System.out.println();
        NetworkConfig.printConfig();
    }

    private static void generateKeys() throws Exception {
        System.out.println(
            "making keys for " + NetworkConfig.NUM_NODES + " nodes..."
        );
        KeyManager.generateAllKeys(
            NetworkConfig.NUM_NODES,
            NetworkConfig.KEYS_DIRECTORY
        );
        System.out.println(
            "keys went to: " + NetworkConfig.KEYS_DIRECTORY + "/ (node* and client*)"
        );
    }

    private static void startNode(int nodeId, ByzantineBehavior behavior)
        throws Exception {
        if (nodeId < 0 || nodeId >= NetworkConfig.NUM_NODES) {
            System.err.println(
                "bad node id: " +
                    nodeId +
                    " (need 0-" +
                    (NetworkConfig.NUM_NODES - 1) +
                    ")"
            );
            return;
        }

        System.out.println("starting node " + nodeId + "...");

        KeyManager keyManager = KeyManager.loadForNode(
            nodeId,
            NetworkConfig.NUM_NODES,
            NetworkConfig.KEYS_DIRECTORY
        );

        Node node = new Node(nodeId, keyManager, behavior);
        node.start();

        System.out.println();
        System.out.println("node " + nodeId + " is up. cmds:");
        System.out.println(
            "  send <destId> <message>  - send msg to another node"
        );
        System.out.println("  broadcast <message>      - send to all nodes");
        System.out.println("  blockchain               - show local chain");
        System.out.println("  quit                     - stop the node");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Node " + nodeId + "> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "send":
                        if (parts.length < 3) {
                            System.out.println("use: send <destId> <message>");
                        } else {
                            int dest = Integer.parseInt(parts[1]);
                            String msg = parts[2];
                            node.send(dest, MessageType.DATA, msg);
                            System.out.println(
                                "sent to node " + dest + ": " + msg
                            );
                        }
                        break;
                    case "broadcast":
                        if (parts.length < 2) {
                            System.out.println("use: broadcast <message>");
                        } else {
                            String msg =
                                parts.length > 2
                                    ? parts[1] + " " + parts[2]
                                    : parts[1];
                            node.broadcast(MessageType.DATA, msg);
                            System.out.println("sent to all: " + msg);
                        }
                        break;
                    case "blockchain":
                        System.out.println(
                            "chain now: " + node.getBlockchain()
                        );
                        break;
                    case "quit":
                    case "exit":
                        node.stop();
                        System.out.println("node stopped");
                        scanner.close();
                        node.close();
                        return;
                    default:
                        System.out.println("dont know this cmd: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("something broke a bit: " + e.getMessage());
            }
        }
    }

    private static void startClient(int clientId) throws Exception {
        System.out.println("starting client " + clientId + "...");

        int clientPort = 6000 + clientId;
        KeyPair clientKeys = loadStaticClientKeyPair(clientId);
        BlockchainClient client = new BlockchainClient(clientId, clientPort, clientKeys);

        client.loadNodeKeys(NetworkConfig.KEYS_DIRECTORY);
        client.start();

        System.out.println();
        System.out.println("client " + clientId + " is up. cmds:");
        System.out.println("  address                        - show this client's EVM address");
        System.out.println("  transfer <to> <valueWei>       - DepCoin transfer (gasPrice=1, gasLimit=21000)");
        System.out.println("  ist_transfer <to> <amount>     - ISTCoin transfer (amount in smallest units)");
        System.out.println("  raw <base64Tx>                 - send a raw Base64(serialized Transaction)");
        System.out.println("  quit                 - stop the client");
        System.out.println();

        long localNonce = 0L;
        // Best effort: clients start at nonce 0 in genesis; if the process restarts,
        // the user can still submit raw transactions with explicit nonces.
        Address istCoin = tryDeriveIstCoinAddressFromNode0(NetworkConfig.KEYS_DIRECTORY);
        if (istCoin != null) {
            System.out.println("ISTCoin (derived) is at: " + istCoin);
        }

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Client " + clientId + "> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "address": {
                        System.out.println("EVM address: " + client.getEvmAddress());
                        break;
                    }
                    case "transfer": {
                        if (parts.length < 3) {
                            System.out.println("use: transfer <toHex> <valueWei>");
                            break;
                        }
                        Address to = Address.fromHexString(parts[1]);
                        Wei value = Wei.of(new BigInteger(parts[2]));
                        Transaction tx = Transaction.create(
                                client.getEvmAddress(),
                                to,
                                value,
                                Bytes.EMPTY,
                                1L,
                                21_000L,
                                localNonce
                        );
                        boolean ok = client.submitTransaction(tx);
                        if (ok) {
                            localNonce++;
                        }
                        System.out.println(ok ? "ok" : "failed");
                        break;
                    }
                    case "ist_transfer": {
                        if (parts.length < 3) {
                            System.out.println("use: ist_transfer <toHex> <amountSmallestUnit>");
                            break;
                        }
                        if (istCoin == null) {
                            System.out.println("ISTCoin address unknown (missing node0 key?)");
                            break;
                        }
                        Address to = Address.fromHexString(parts[1]);
                        long amount = Long.parseLong(parts[2]);
                        Bytes calldata = Bytes.fromHexString(
                                "a9059cbb" + addrArg(to) + uint256Arg(amount)
                        );
                        Transaction tx = Transaction.create(
                                client.getEvmAddress(),
                                istCoin,
                                Wei.ZERO,
                                calldata,
                                1L,
                                200_000L,
                                localNonce
                        );
                        boolean ok = client.submitTransaction(tx);
                        if (ok) {
                            localNonce++;
                        }
                        System.out.println(ok ? "ok" : "failed");
                        break;
                    }
                    case "raw": {
                        if (parts.length < 2) {
                            System.out.println("use: raw <base64Tx>");
                            break;
                        }
                        String data = parts.length > 2 ? (parts[1] + " " + parts[2]) : parts[1];
                        System.out.println("sending raw tx... (expects Base64(Transaction bytes))");
                        boolean ok = client.submitRequest(data);
                        System.out.println(ok ? "ok" : "failed");
                        break;
                    }
                    case "quit":
                    case "exit":
                        client.stop();
                        System.out.println("client stopped");
                        scanner.close();
                        return;
                    default:
                        System.out.println("dont know this cmd: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("something broke a bit: " + e.getMessage());
            }
        }
    }

    private static KeyPair loadStaticClientKeyPair(int clientId) throws Exception {
        String base = NetworkConfig.KEYS_DIRECTORY + "/client" + clientId;
        String privPath = base + ".key";
        String pubPath = base + ".pub";

        if (!Files.exists(Path.of(privPath)) || !Files.exists(Path.of(pubPath))) {
            throw new IllegalStateException(
                "Missing static client keys for client " + clientId +
                " (expected " + privPath + " and " + pubPath + "). Run 'genkeys' first."
            );
        }

        PrivateKey privateKey = CryptoUtils.loadPrivateKey(privPath);
        PublicKey publicKey = CryptoUtils.loadPublicKey(pubPath);
        return new KeyPair(publicKey, privateKey);
    }

    private static void runDemoTest() throws Exception {
        System.out.println("Stage 1 demo is deprecated; use demo_stage2_transfer or demo_stage2_erc20.");
    }

    private static void runFaultInjectionDemo() throws Exception {
        System.out.println("starting fault demo...");
        NetworkFaultController.clearRules();
        NetworkFaultController.addDropRule(0, 1, MessageType.PREPARE, 8);
        NetworkFaultController.addDelayRule(
            0,
            2,
            MessageType.PRE_COMMIT,
            300,
            10
        );
        NetworkFaultController.addDuplicateRule(0, 3, MessageType.COMMIT, 2, 8);

        runScenario(new HashMap<>(), "demo-faults-tx-1");
        NetworkFaultController.clearRules();
    }

    private static void runByzantineDemo() throws Exception {
        System.out.println("starting byz demo (leader goes weird)...");
        Map<Integer, ByzantineBehavior> behaviorById = new HashMap<>();
        behaviorById.put(0, ByzantineBehavior.EQUIVOCATE_LEADER);
        runScenario(behaviorById, "demo-byz-tx-1");
    }

    private static void runPersistenceDemo() throws Exception {
        System.out.println("starting restart demo...");
        Path stateDir = Files.createTempDirectory("depchain-demo-state-");

        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        List<Node> nodes = new ArrayList<>();
        BlockchainClient client = null;

        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                Node node = new Node(
                    i,
                    keyManagers.get(i),
                    ByzantineBehavior.HONEST,
                    stateDir.toString(),
                    true
                );
                node.start();
                nodes.add(node);
            }

                client = new BlockchainClient(101, 6101, new KeyPair(
                    keyManagers.get(0).getPublicKey(0),
                    keyManagers.get(0).getPrivateKey()
                ));
            Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
            }
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

                Address sender = client.getEvmAddress();
                long nonce = nodes.get(0).getEvmService().getNonce(sender);
                Transaction firstTx = Transaction.create(
                    sender,
                    freshAddressForDemo(),
                    Wei.of(1_000L),
                    Bytes.EMPTY,
                    1L,
                    21_000L,
                    nonce
                );
                boolean first = client.submitTransaction(firstTx);
            System.out.println("first req commited: " + first);

            Node node3 = nodes.get(3);
            node3.stop();
            nodes.remove(3);

            Node restarted = new Node(
                3,
                keyManagers.get(3),
                ByzantineBehavior.HONEST,
                stateDir.toString(),
                true
            );
            restarted.start();
            nodes.add(restarted);
            System.out.println(
                "node 3 came back, chain: " + restarted.getBlockchain()
            );

                Transaction secondTx = Transaction.create(
                    sender,
                    freshAddressForDemo(),
                    Wei.of(2_000L),
                    Bytes.EMPTY,
                    1L,
                    21_000L,
                    nonce + 1
                );
                boolean second = client.submitTransaction(secondTx);
            System.out.println("second req commited: " + second);

            Thread.sleep(1500);
            for (Node node : nodes) {
                System.out.println(
                    "Node " +
                        node.getNodeId() +
                        " chain=" +
                        node.getBlockchain()
                );
            }
        } finally {
            if (client != null) {
                client.stop();
            }
            for (Node node : nodes) {
                node.stop();
            }
        }
    }

    private static void runScenario(
        Map<Integer, ByzantineBehavior> behaviorById,
        String requestData
    ) throws Exception {
        Path stateDir = Files.createTempDirectory("depchain-demo-scenario-");
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(
            NetworkConfig.NUM_NODES
        );
        List<Node> nodes = new ArrayList<>();
        BlockchainClient client = null;

        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                ByzantineBehavior behavior = behaviorById.getOrDefault(
                    i,
                    ByzantineBehavior.HONEST
                );
                Node node = new Node(
                    i,
                    keyManagers.get(i),
                    behavior,
                    stateDir.toString(),
                    false
                );
                node.start();
                nodes.add(node);
            }

            Thread.sleep(700);

            client = new BlockchainClient(102, 6102, new KeyPair(
                    keyManagers.get(0).getPublicKey(0),
                    keyManagers.get(0).getPrivateKey()
            ));
            Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
            }
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

            // Backwards-compat: interpret requestData as a Base64(Transaction bytes).
            // This keeps the Stage 1 fault demos working while using Stage 2 payloads.
            boolean ok = client.submitRequest(requestData);
            System.out.println("req commited: " + ok);

            Thread.sleep(2000);
            for (Node node : nodes) {
                System.out.println(
                    "Node " +
                        node.getNodeId() +
                        " behavior=" +
                        behaviorById.getOrDefault(
                            node.getNodeId(),
                            ByzantineBehavior.HONEST
                        ) +
                        " view=" +
                        node.getCurrentView() +
                        " chain=" +
                        node.getBlockchain()
                );
            }
        } finally {
            if (client != null) {
                client.stop();
            }
            for (Node node : nodes) {
                node.stop();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stage 2 demos
    // -------------------------------------------------------------------------

    private static void runStage2DepCoinTransferDemo() throws Exception {
        System.out.println("starting Stage 2 DepCoin transfer demo...");
        Path stateDir = Files.createTempDirectory("depchain-demo-stage2-transfer-");
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        List<Node> nodes = new ArrayList<>();

        Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }

        BlockchainClient client = null;
        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                Node node = new Node(i, keyManagers.get(i), ByzantineBehavior.HONEST, stateDir.toString(), true);
                node.start();
                nodes.add(node);
            }

            Thread.sleep(700);

            client = new BlockchainClient(0, 6000, new KeyPair(
                    keyManagers.get(0).getPublicKey(0),
                    keyManagers.get(0).getPrivateKey()
            ));
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

            Address sender = client.getEvmAddress();
            Address recipient = freshAddressForDemo();
            long nonce = nodes.get(0).getEvmService().getNonce(sender);

            System.out.println("sender=" + sender + " nonce=" + nonce);
            System.out.println("recipient=" + recipient);

            Transaction tx = Transaction.create(sender, recipient, Wei.of(10_000L), Bytes.EMPTY, 1L, 21_000L, nonce);
            boolean ok = client.submitTransaction(tx);
            System.out.println("committed=" + ok);

            Thread.sleep(1500);
            for (Node n : nodes) {
                System.out.println(
                        "Node " + n.getNodeId() +
                        " senderBal=" + n.getEvmService().getBalance(sender) +
                        " recipientBal=" + n.getEvmService().getBalance(recipient)
                );
            }
        } finally {
            if (client != null) client.stop();
            for (Node n : nodes) n.stop();
        }
    }

    private static void runStage2Erc20TransferDemo() throws Exception {
        System.out.println("starting Stage 2 ERC-20 transfer demo...");
        Path stateDir = Files.createTempDirectory("depchain-demo-stage2-erc20-");
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        List<Node> nodes = new ArrayList<>();

        Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }

        BlockchainClient client = null;
        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                Node node = new Node(i, keyManagers.get(i), ByzantineBehavior.HONEST, stateDir.toString(), true);
                node.start();
                nodes.add(node);
            }

            Thread.sleep(700);

            client = new BlockchainClient(0, 6000, new KeyPair(
                    keyManagers.get(0).getPublicKey(0),
                    keyManagers.get(0).getPrivateKey()
            ));
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

            Address sender = client.getEvmAddress();
            Address istCoin = Address.contractAddress(
                    EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0)),
                    0
            );
            Address recipient = freshAddressForDemo();
            long nonce = nodes.get(0).getEvmService().getNonce(sender);

            System.out.println("ISTCoin=" + istCoin);
            System.out.println("sender=" + sender + " nonce=" + nonce);
            System.out.println("recipient=" + recipient);

            long amount = 100_00L; // 100.00 IST
            Bytes calldata = Bytes.fromHexString("a9059cbb" + addrArg(recipient) + uint256Arg(amount));
            Transaction tx = Transaction.create(sender, istCoin, Wei.ZERO, calldata, 1L, 200_000L, nonce);

            boolean ok = client.submitTransaction(tx);
            System.out.println("committed=" + ok);

            Thread.sleep(1500);

            for (Node n : nodes) {
                BigInteger bal = istBalanceOf(n.getEvmService(), istCoin, recipient);
                System.out.println("Node " + n.getNodeId() + " IST.balanceOf(recipient)=" + bal);
            }
        } finally {
            if (client != null) client.stop();
            for (Node n : nodes) n.stop();
        }
    }

    private static void runStage2ByzantineClientDemo() throws Exception {
        System.out.println("starting Stage 2 byzantine client demo...");
        Path stateDir = Files.createTempDirectory("depchain-demo-stage2-byzclient-");
        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        List<Node> nodes = new ArrayList<>();

        Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
        }

        BlockchainClient attacker = null;
        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                Node node = new Node(i, keyManagers.get(i), ByzantineBehavior.HONEST, stateDir.toString(), true);
                node.start();
                nodes.add(node);
            }
            Thread.sleep(700);

            attacker = new BlockchainClient(1, 6001); // fresh key, not genesis-funded
            attacker.setNodePublicKeys(nodePublicKeys);
            attacker.start();

            Address victim = EvmService.deriveAddress(keyManagers.get(0).getPublicKey(0));
            long nonce = nodes.get(0).getEvmService().getNonce(victim);
            Transaction malicious = Transaction.create(
                    victim,
                    freshAddressForDemo(),
                    Wei.of(1_000L),
                    Bytes.EMPTY,
                    1L,
                    21_000L,
                    nonce
            );

            boolean ok = attacker.submitTransaction(malicious);
            System.out.println("spoof attempt committed=" + ok + " (expected false)");
        } finally {
            if (attacker != null) attacker.stop();
            for (Node n : nodes) n.stop();
        }
    }

    private static void runStage2SixClientsDemo() throws Exception {
        System.out.println("starting Stage 2 6-clients demo...");
        System.out.println("note: requires keys/ generated via: mvn exec:java '-Dexec.args=genkeys'");

        Path stateDir = Files.createTempDirectory("depchain-demo-stage2-6clients-");

        List<Node> nodes = new ArrayList<>();
        Map<Integer, KeyManager> nodeKeyManagers = new HashMap<>();
        Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();

        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            KeyManager km = KeyManager.loadForNode(i, NetworkConfig.NUM_NODES, NetworkConfig.KEYS_DIRECTORY);
            nodeKeyManagers.put(i, km);
            nodePublicKeys.put(i, km.getPublicKey(i));
        }

        List<BlockchainClient> clients = new ArrayList<>();
        try {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                Node node = new Node(i, nodeKeyManagers.get(i), ByzantineBehavior.HONEST, stateDir.toString(), true);
                node.start();
                nodes.add(node);
            }

            Thread.sleep(700);

            // Start 6 static clients from keys/clientX.{key,pub}
            for (int clientId = 0; clientId < NetworkConfig.NUM_STATIC_CLIENTS; clientId++) {
                KeyPair kp = loadKeyPairFromFiles(NetworkConfig.KEYS_DIRECTORY + "/client" + clientId);
                BlockchainClient c = new BlockchainClient(clientId, 6000 + clientId, kp);
                c.setNodePublicKeys(nodePublicKeys);
                c.start();
                clients.add(c);
            }

            // Each client submits one DepCoin transfer to the next client's address.
            Node reference = nodes.get(0);
            for (int clientId = 0; clientId < NetworkConfig.NUM_STATIC_CLIENTS; clientId++) {
                BlockchainClient senderClient = clients.get(clientId);
                Address sender = senderClient.getEvmAddress();

                Address recipient = clients.get((clientId + 1) % NetworkConfig.NUM_STATIC_CLIENTS).getEvmAddress();
                long nonce = reference.getEvmService().getNonce(sender);

                Transaction tx = Transaction.create(
                        sender,
                        recipient,
                        Wei.of(1_000L),
                        Bytes.EMPTY,
                        1L,
                        21_000L,
                        nonce
                );

                boolean ok = senderClient.submitTransaction(tx);
                System.out.println("client" + clientId + " sent transfer -> committed=" + ok);
            }

            Thread.sleep(2000);

            // Print a compact summary from node 0.
            System.out.println("--- balances (node0 view) ---");
            for (int clientId = 0; clientId < NetworkConfig.NUM_STATIC_CLIENTS; clientId++) {
                Address a = clients.get(clientId).getEvmAddress();
                System.out.println("client" + clientId + " " + a + " balance=" + reference.getEvmService().getBalance(a));
            }
        } finally {
            for (BlockchainClient c : clients) {
                try { c.stop(); } catch (Exception ignored) {}
            }
            for (Node n : nodes) {
                try { n.stop(); } catch (Exception ignored) {}
            }
        }
    }

    private static KeyPair loadKeyPairFromFiles(String basePath) throws Exception {
        PrivateKey priv = CryptoUtils.loadPrivateKey(basePath + ".key");
        PublicKey pub = CryptoUtils.loadPublicKey(basePath + ".pub");
        return new KeyPair(pub, priv);
    }

    private static Address tryDeriveIstCoinAddressFromNode0(String keysDirectory) {
        try {
            PublicKey node0 = CryptoUtils.loadPublicKey(keysDirectory + "/node0.pub");
            Address deployer = EvmService.deriveAddress(node0);
            return Address.contractAddress(deployer, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private static Address freshAddressForDemo() {
        // Use the same pattern as tests: last 2 bytes change.
        byte[] bytes = new byte[20];
        int s = (int) (System.nanoTime() & 0xFFFF);
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

    private static BigInteger istBalanceOf(EvmService evm, Address contract, Address account) {
        Bytes calldata = Bytes.fromHexString("70a08231" + addrArg(account));
        Bytes result = evm.callContract(account, contract, calldata, 100_000L);
        return result.isEmpty() ? BigInteger.ZERO : new BigInteger(1, result.toArray());
    }
}
