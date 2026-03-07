package depchain;

import depchain.client.BlockchainClient;
import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;
import depchain.net.MessageType;
import depchain.node.Node;

import java.util.Scanner;

/**
 * Main application for DepChain.
 * 
 * Usage:
 *   mvn exec:java -Dexec.args="genkeys"           - Generate keys for all nodes
 *   mvn exec:java -Dexec.args="node <nodeId>"     - Start a node
 *   mvn exec:java -Dexec.args="client <clientId>" - Start a client
 *   mvn exec:java -Dexec.args="test"              - Run a simple test with all nodes
 */
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
                        System.err.println("Error: Node ID required");
                        printUsage();
                        return;
                    }
                    int nodeId = Integer.parseInt(args[1]);
                    startNode(nodeId);
                    break;
                case "client":
                    if (args.length < 2) {
                        System.err.println("Error: Client ID required");
                        printUsage();
                        return;
                    }
                    int clientId = Integer.parseInt(args[1]);
                    startClient(clientId);
                    break;
                case "test":
                    System.out.println("Tests not implemented yet.");
                    break;
                case "config":
                    NetworkConfig.printConfig();
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("DepChain - Dependable Blockchain System");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  mvn exec:java -Dexec.args=\"genkeys\"         - Generate keys for all nodes");
        System.out.println("  mvn exec:java -Dexec.args=\"node <id>\"       - Start node (id: 0-3)");
        System.out.println("  mvn exec:java -Dexec.args=\"client <id>\"     - Start client (id: 100+)");
        System.out.println("  mvn exec:java -Dexec.args=\"test\"            - Run test with all nodes");
        System.out.println("  mvn exec:java -Dexec.args=\"config\"          - Show network configuration");
        System.out.println();
        NetworkConfig.printConfig();
    }

    private static void generateKeys() throws Exception {
        System.out.println("Generating keys for " + NetworkConfig.NUM_NODES + " nodes...");
        KeyManager.generateAllKeys(NetworkConfig.NUM_NODES, NetworkConfig.KEYS_DIRECTORY);
        System.out.println("Keys saved to: " + NetworkConfig.KEYS_DIRECTORY + "/");
    }

    private static void startNode(int nodeId) throws Exception {
        if (nodeId < 0 || nodeId >= NetworkConfig.NUM_NODES) {
            System.err.println("Invalid node ID: " + nodeId + " (must be 0-" + (NetworkConfig.NUM_NODES - 1) + ")");
            return;
        }

        System.out.println("Starting Node " + nodeId + "...");
        
        KeyManager keyManager = KeyManager.loadForNode(
            nodeId, NetworkConfig.NUM_NODES, NetworkConfig.KEYS_DIRECTORY
        );

        Node node = new Node(nodeId, keyManager);
        node.start();

        System.out.println();
        System.out.println("Node " + nodeId + " is running. Commands:");
        System.out.println("  send <destId> <message>  - Send message to another node");
        System.out.println("  broadcast <message>      - Broadcast to all nodes");
        System.out.println("  blockchain               - Show local blockchain");
        System.out.println("  quit                     - Stop the node");
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
                            System.out.println("Usage: send <destId> <message>");
                        } else {
                            int dest = Integer.parseInt(parts[1]);
                            String msg = parts[2];
                            node.send(dest, MessageType.DATA, msg);
                            System.out.println("Sent to node " + dest + ": " + msg);
                        }
                        break;
                    case "broadcast":
                        if (parts.length < 2) {
                            System.out.println("Usage: broadcast <message>");
                        } else {
                            String msg = parts.length > 2 ? parts[1] + " " + parts[2] : parts[1];
                            node.broadcast(MessageType.DATA, msg);
                            System.out.println("Broadcasted: " + msg);
                        }
                        break;
                    case "blockchain":
                        System.out.println("Blockchain: " + node.getBlockchain());
                        break;
                    case "quit":
                    case "exit":
                        node.stop();
                        System.out.println("Node stopped.");
                        return;
                    default:
                        System.out.println("Unknown command: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    private static void startClient(int clientId) throws Exception {
        System.out.println("Starting Client " + clientId + "...");
        
        int clientPort = 6000 + clientId;
        BlockchainClient client = new BlockchainClient(clientId, clientPort);
        
        client.loadNodeKeys(NetworkConfig.KEYS_DIRECTORY);
        client.start();

        System.out.println();
        System.out.println("Client " + clientId + " is running. Commands:");
        System.out.println("  append <data>        - Submit request to append data to blockchain");
        System.out.println("  send <nodeId> <data> - Send request to specific node");
        System.out.println("  quit                 - Stop the client");
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
                            System.out.println("Usage: append <data>");
                        } else {
                            String data = parts.length > 2 ? parts[1] + " " + parts[2] : parts[1];
                            System.out.println("Submitting request (encrypted)...");
                            boolean success = client.submitRequest(data);
                            System.out.println(success ? "Request confirmed!" : "Request failed/timed out");
                        }
                        break;
                    case "send":
                        if (parts.length < 3) {
                            System.out.println("Usage: send <nodeId> <data>");
                        } else {
                            int nodeId = Integer.parseInt(parts[1]);
                            String data = parts[2];
                            System.out.println("Sending encrypted request to node " + nodeId + "...");
                            boolean success = client.submitRequestToNode(nodeId, data);
                            System.out.println(success ? "Request confirmed!" : "Request failed/timed out");
                        }
                        break;
                    case "quit":
                    case "exit":
                        client.stop();
                        System.out.println("Client stopped.");
                        return;
                    default:
                        System.out.println("Unknown command: " + cmd);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }
}
