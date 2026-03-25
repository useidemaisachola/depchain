package depchain;

import depchain.client.BlockchainClient;
import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;
import depchain.net.MessageType;
import depchain.net.fault.NetworkFaultController;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

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
            "  mvn exec:java -Dexec.args=\"genkeys\"         - make keys for all nodes"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"node <id> [behavior]\" - start a node (HONEST|SILENT|EQUIVOCATE_LEADER|INVALID_VOTE_SIGNATURE)"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"client <id>\"     - start a client (id: 100+)"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"test\"            - run the basic all-in-one demo"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_faults\"     - run the fault demo"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_byz\"        - run the weird leader demo"
        );
        System.out.println(
            "  mvn exec:java -Dexec.args=\"demo_persist\"    - run the restart demo"
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
            "keys went to: " + NetworkConfig.KEYS_DIRECTORY + "/"
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
        BlockchainClient client = new BlockchainClient(clientId, clientPort);

        client.loadNodeKeys(NetworkConfig.KEYS_DIRECTORY);
        client.start();

        System.out.println();
        System.out.println("client " + clientId + " is up. cmds:");
        System.out.println(
            "  append <data>        - send a req to append data"
        );
        System.out.println("  send <nodeId> <data> - send a req to one node");
        System.out.println("  quit                 - stop the client");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Client " + clientId + "> ");
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "append":
                        if (parts.length < 2) {
                            System.out.println("use: append <data>");
                        } else {
                            String data =
                                parts.length > 2
                                    ? parts[1] + " " + parts[2]
                                    : parts[1];
                            System.out.println("sending req (encrypted)...");
                            boolean success = client.submitRequest(data);
                            System.out.println(
                                success
                                    ? "req went thru"
                                    : "req failed or timed out"
                            );
                        }
                        break;
                    case "send":
                        if (parts.length < 3) {
                            System.out.println("use: send <nodeId> <data>");
                        } else {
                            int nodeId = Integer.parseInt(parts[1]);
                            String data = parts[2];
                            System.out.println(
                                "sending encrypted req to node " +
                                    nodeId +
                                    "..."
                            );
                            boolean success = client.submitRequestToNode(
                                nodeId,
                                data
                            );
                            System.out.println(
                                success
                                    ? "req went thru"
                                    : "req failed or timed out"
                            );
                        }
                        break;
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

    private static void runDemoTest() throws Exception {
        System.out.println(
            "starting basic in-proc demo with " +
                NetworkConfig.NUM_NODES +
                " nodes..."
        );
        Path stateDir = Files.createTempDirectory("depchain-demo-basic-");

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
                    false
                );
                node.start();
                nodes.add(node);
            }

            Thread.sleep(700);

            client = new BlockchainClient(100, 6100);
            Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
            }
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

            boolean r1 = client.submitRequest("demo-tx-1");
            boolean r2 = client.submitRequest("demo-tx-2");
            System.out.println("req results kinda: " + r1 + ", " + r2);

            Thread.sleep(2500);

            for (Node node : nodes) {
                System.out.println(
                    "Node " +
                        node.getNodeId() +
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

            client = new BlockchainClient(101, 6101);
            Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
            }
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

            boolean first = client.submitRequest("demo-persist-tx-1");
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

            boolean second = client.submitRequest("demo-persist-tx-2");
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

            client = new BlockchainClient(102, 6102);
            Map<Integer, PublicKey> nodePublicKeys = new HashMap<>();
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                nodePublicKeys.put(i, keyManagers.get(i).getPublicKey(i));
            }
            client.setNodePublicKeys(nodePublicKeys);
            client.start();

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
}
