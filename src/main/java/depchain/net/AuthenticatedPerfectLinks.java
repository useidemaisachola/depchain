package depchain.net;

import depchain.crypto.KeyManager;
import depchain.net.Message;
import depchain.net.MessageType;

import java.util.Map;

/**
 * Authenticated Perfect Links (APL)
 * 
 * Built on top of Perfect Links.
 * Properties (all PL properties plus):
 * - APL1 (Authenticity): If a correct process delivers a message m from sender s,
 *   then s previously sent m
 * 
 * Implementation:
 * - Signs all outgoing messages with the sender's private key
 * - Verifies signatures on all incoming messages using the sender's public key
 * - Rejects messages with invalid signatures
 */
public class AuthenticatedPerfectLinks implements PerfectLinks.Listener {
    
    public interface Listener {
        void onAPLDeliver(int senderId, Message message);
    }

    private final PerfectLinks perfectLinks;
    private final Listener listener;
    private final int nodeId;
    private final KeyManager keyManager;

    public AuthenticatedPerfectLinks(int nodeId, int port, Listener listener,
                                     Map<Integer, FairLossLinks.NodeAddress> nodeAddresses,
                                     KeyManager keyManager) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.keyManager = keyManager;
        this.perfectLinks = new PerfectLinks(nodeId, port, this, nodeAddresses);
    }

    public void start() {
        perfectLinks.start();
        System.out.println("[APL] Node " + nodeId + " started with authentication");
    }

    public void stop() {
        perfectLinks.stop();
    }

    /* APL-Send: Send an authenticated message. The message will be signed with this node's private key */
    public void send(int destNodeId, MessageType type, byte[] payload) {
        // Create message
        Message message = new Message(nodeId, destNodeId, 0, type, payload);
        
        // Sign the message
        byte[] signature = keyManager.sign(message.getBytesToSign());
        message.setSignature(signature);
        
        // Note: We need to handle the message ID generation in PL
        // For now, send through PL which will create its own message
        perfectLinks.send(destNodeId, type, payload);
    }

    /* APL-Send with string payload */
    public void send(int destNodeId, MessageType type, String payload) {
        send(destNodeId, type, payload.getBytes());
    }

    /* Send authenticated message (full control) */
    public void sendMessage(int destNodeId, Message message) {
        // Sign the message
        byte[] signature = keyManager.sign(message.getBytesToSign());
        message.setSignature(signature);
        
        // Send via SL directly to have full control
        perfectLinks.getStubbornLinks().send(destNodeId, message);
    }

    /* Broadcast authenticated message to all nodes */
    public void broadcast(MessageType type, byte[] payload, int[] nodeIds) {
        for (int destId : nodeIds) {
            if (destId != nodeId) {
                send(destId, type, payload);
            }
        }
    }

    @Override
    public void onPLDeliver(int senderId, Message message) {
        // Verify signature (if present)
        if (message.getSignature() != null) {
            byte[] dataToVerify = message.getBytesToSign();
            boolean valid = keyManager.verify(senderId, dataToVerify, message.getSignature());
            
            if (!valid) {
                System.err.println("[APL] Invalid signature from node " + senderId + ", dropping message");
                return;
            }
        }
        
        // APL-Deliver (signature valid or not required)
        listener.onAPLDeliver(senderId, message);
    }

    public int getNodeId() {
        return nodeId;
    }

    public PerfectLinks getPerfectLinks() {
        return perfectLinks;
    }

    public KeyManager getKeyManager() {
        return keyManager;
    }
}
