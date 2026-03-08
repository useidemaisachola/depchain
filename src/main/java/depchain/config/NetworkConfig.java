package depchain.config;

import depchain.net.FairLossLinks.NodeAddress;
import java.util.*;

/* Static configuration for the DepChain network.*/
public class NetworkConfig {

    // Number of nodes (n = 3f + 1)
    public static final int NUM_NODES = 4;

    // Maximum Byzantine faults (f)
    public static final int MAX_FAULTS = 1;

    // Quorum size (2f + 1)
    public static final int QUORUM_SIZE = 2 * MAX_FAULTS + 1;

    // Base port for nodes
    public static final int BASE_PORT = 5000;

    // Host for all nodes
    public static final String DEFAULT_HOST = "localhost";

    // Keys directory
    public static final String KEYS_DIRECTORY = "keys";

    // Persistent state directory
    public static final String STATE_DIRECTORY = "state";

    /*Get the port for a specific node*/
    public static int getNodePort(int nodeId) {
        return BASE_PORT + nodeId;
    }

    /*Get the host for a specific node*/
    public static String getNodeHost(int nodeId) {
        return DEFAULT_HOST;
    }

    /*Get all node addresses as a map*/
    public static Map<Integer, NodeAddress> getAllNodeAddresses() {
        Map<Integer, NodeAddress> addresses = new HashMap<>();
        for (int i = 0; i < NUM_NODES; i++) {
            addresses.put(i, new NodeAddress(getNodeHost(i), getNodePort(i)));
        }
        return addresses;
    }

    /*Get a list of all node IDs*/
    public static int[] getAllNodeIds() {
        int[] ids = new int[NUM_NODES];
        for (int i = 0; i < NUM_NODES; i++) {
            ids[i] = i;
        }
        return ids;
    }

    /* Check if a quorum is reached*/
    public static boolean isQuorum(int count) {
        return count >= QUORUM_SIZE;
    }

    /*Get the leader*/
    public static int getLeader(int viewNumber) {
        return viewNumber % NUM_NODES;
    }

    /*Print network configuration*/
    public static void printConfig() {
        System.out.println("=== DepChain Network Configuration ===");
        System.out.println("Total nodes (n): " + NUM_NODES);
        System.out.println("Max faults (f): " + MAX_FAULTS);
        System.out.println("Quorum size (2f+1): " + QUORUM_SIZE);
        System.out.println("Nodes:");
        for (int i = 0; i < NUM_NODES; i++) {
            System.out.println(
                "  Node " + i + ": " + getNodeHost(i) + ":" + getNodePort(i)
            );
        }
        System.out.println("======================================");
    }
}
