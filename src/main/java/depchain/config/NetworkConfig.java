package depchain.config;

import depchain.net.FairLossLinks.NodeAddress;
import java.util.*;

public class NetworkConfig {

    public static final int NUM_NODES = 4;

    public static final int MAX_FAULTS = 1;

    public static final int QUORUM_SIZE = 2 * MAX_FAULTS + 1;

    public static final int BASE_PORT = 5000;

    public static final String DEFAULT_HOST = "localhost";

    public static final String KEYS_DIRECTORY = "keys";

    public static final String STATE_DIRECTORY = "state";

    public static int getNodePort(int nodeId) {
        return BASE_PORT + nodeId;
    }

    public static String getNodeHost(int nodeId) {
        return DEFAULT_HOST;
    }

    public static Map<Integer, NodeAddress> getAllNodeAddresses() {
        Map<Integer, NodeAddress> addresses = new HashMap<>();
        for (int i = 0; i < NUM_NODES; i++) {
            addresses.put(i, new NodeAddress(getNodeHost(i), getNodePort(i)));
        }
        return addresses;
    }

    public static int[] getAllNodeIds() {
        int[] ids = new int[NUM_NODES];
        for (int i = 0; i < NUM_NODES; i++) {
            ids[i] = i;
        }
        return ids;
    }

    public static boolean isQuorum(int count) {
        return count >= QUORUM_SIZE;
    }

    public static int getLeader(int viewNumber) {
        return viewNumber % NUM_NODES;
    }

    public static void printConfig() {
        System.out.println("=== depchain net config ===");
        System.out.println("nodes total: " + NUM_NODES);
        System.out.println("max faults: " + MAX_FAULTS);
        System.out.println("quorum need: " + QUORUM_SIZE);
        System.out.println("nodes:");
        for (int i = 0; i < NUM_NODES; i++) {
            System.out.println(
                "  node " + i + " -> " + getNodeHost(i) + ":" + getNodePort(i)
            );
        }
        System.out.println("============================");
    }
}
