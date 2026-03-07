package depchain.node;

import depchain.config.NetworkConfig;
import depchain.crypto.CryptoUtils;
import depchain.crypto.KeyManager;
import depchain.client.ClientRequest;
import depchain.client.EncryptedPayload;
import depchain.net.Message;
import depchain.net.MessageType;
import depchain.net.AuthenticatedPerfectLinks;
import depchain.net.FairLossLinks.NodeAddress;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a blockchain node in the DepChain network.
 * 
 * Each node:
 * - Has a unique ID (0 to n-1)
 * - Communicates via the APL stack (UDP -> FL -> SL -> PL -> APL)
 * - Maintains a local blockchain (list of appended strings)
 * - Participates in HotStuff consensus (to be implemented)
 */
public class Node implements AuthenticatedPerfectLinks.Listener, AutoCloseable {

    private final int nodeId;
    private final int port;
    private final KeyManager keyManager;
    private final AuthenticatedPerfectLinks apl;
    
    // Simple blockchain: list of appended strings
    private final List<String> blockchain;
    
    // Listener for application-level events
    private NodeListener listener;

    public interface NodeListener {
        void onMessageReceived(int senderId, Message message);
        void onBlockAppended(String data);
    }

    public Node(int nodeId, KeyManager keyManager) {
        this.nodeId = nodeId;
        this.port = NetworkConfig.getNodePort(nodeId);
        this.keyManager = keyManager;
        this.blockchain = new CopyOnWriteArrayList<>();
        
        // Get all node addresses
        Map<Integer, NodeAddress> nodeAddresses = NetworkConfig.getAllNodeAddresses();
        
        // Create APL stack
        this.apl = new AuthenticatedPerfectLinks(
            nodeId,
            port,
            this,
            nodeAddresses,
            keyManager
        );
    }

    public void setListener(NodeListener listener) {
        this.listener = listener;
    }

    public void start() {
        apl.start();
        System.out.println("[Node " + nodeId + "] Started on port " + port);
    }

    public void stop() {
        apl.stop();
        System.out.println("[Node " + nodeId + "] Stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /* Send a message to another node */
    public void send(int destNodeId, MessageType type, String payload) {
        apl.send(destNodeId, type, payload);
    }

    /* Broadcast a message to all other nodes */
    public void broadcast(MessageType type, String payload) {
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            if (i != nodeId) {
                send(i, type, payload);
            }
        }
    }

    /* Append a string to the local blockchain (In full implementation, this would go through consensus) */
    public void appendToBlockchain(String data) {
        blockchain.add(data);
        System.out.println("[Node " + nodeId + "] Appended to blockchain: " + data);
        if (listener != null) {
            listener.onBlockAppended(data);
        }
    }

    /* Get the current blockchain state */
    public List<String> getBlockchain() {
        return Collections.unmodifiableList(blockchain);
    }

    @Override
    public void onAPLDeliver(int senderId, Message message) {
        System.out.println("[Node " + nodeId + "] Received from Node " + senderId + ": " + message);
        
        // Handle different message types
        switch (message.getType()) {
            case DATA:
                handleDataMessage(senderId, message);
                break;
            case CLIENT_REQUEST:
                handleClientRequest(senderId, message);
                break;
            // Future: HotStuff consensus messages
            case PREPARE:
            case PRE_COMMIT:
            case COMMIT:
            case DECIDE:
            case NEW_VIEW:
                handleConsensusMessage(senderId, message);
                break;
            default:
                System.out.println("[Node " + nodeId + "] Unknown message type: " + message.getType());
        }
        
        if (listener != null) {
            listener.onMessageReceived(senderId, message);
        }
    }

    private void handleDataMessage(int senderId, Message message) {
        // Simple echo for testing
        System.out.println("[Node " + nodeId + "] Data from Node " + senderId + ": " 
                          + message.getPayloadAsString());
    }

    private void handleClientRequest(int senderId, Message message) {
        try {
            // Try to decrypt if it's an encrypted payload
            byte[] payload = message.getPayload();
            String data;
            int clientId = senderId;
            
            try {
                // Attempt to deserialize as EncryptedPayload
                EncryptedPayload encrypted = EncryptedPayload.deserialize(payload);
                
                // Decrypt using this node's private key
                byte[] decryptedBytes = CryptoUtils.decryptHybrid(
                    encrypted.getEncryptedKey(),
                    encrypted.getIv(),
                    encrypted.getEncryptedData(),
                    keyManager.getPrivateKey()
                );
                
                // Deserialize the ClientRequest
                ClientRequest request = ClientRequest.deserialize(decryptedBytes);
                data = request.getData();
                clientId = request.getClientId();
                
                System.out.println("[Node " + nodeId + "] Decrypted client request from client " 
                                  + clientId + ": '" + data + "'");
            } catch (Exception e) {
                // Not encrypted, use plain payload
                data = message.getPayloadAsString();
                System.out.println("[Node " + nodeId + "] Plain client request: '" + data + "'");
            }
            
            // TODO: Trigger HotStuff consensus here
            // For now, just append locally
            appendToBlockchain(data);
            
            // Send reply to client (will be implemented when we have client address tracking)
            System.out.println("[Node " + nodeId + "] Request processed for client " + clientId);
            
        } catch (Exception e) {
            System.err.println("[Node " + nodeId + "] Error processing client request: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleConsensusMessage(int senderId, Message message) {
        // Placeholder for HotStuff consensus messages
        System.out.println("[Node " + nodeId + "] Consensus message from Node " + senderId 
                          + ": " + message.getType());
        // TODO: Implement HotStuff protocol
    }

    // Getters
    public int getNodeId() { return nodeId; }
    public int getPort() { return port; }
    public AuthenticatedPerfectLinks getAPL() { return apl; }
    public KeyManager getKeyManager() { return keyManager; }

    @Override
    public String toString() {
        return "Node{id=" + nodeId + ", port=" + port + ", blockchain=" + blockchain.size() + " items}";
    }
}
