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

    public AuthenticatedPerfectLinks(
        int nodeId,
        int port,
        Listener listener,
        Map<Integer, FairLossLinks.NodeAddress> nodeAddresses,
        KeyManager keyManager
    ) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.keyManager = keyManager;
        this.perfectLinks = new PerfectLinks(nodeId, port, this, nodeAddresses);
    }

    public void start() {
        perfectLinks.start();
        System.out.println(
            "[APL] Node " + nodeId + " started with authentication"
        );
    }

    public void stop() {
        perfectLinks.stop();
    }

    /* APL-Send: Send an authenticated message. The message will be signed with this node's private key */
    public void send(int destNodeId, MessageType type, byte[] payload) {
        long msgId = perfectLinks.nextMessageId();
        Message message = new Message(nodeId, destNodeId, msgId, type, payload);
        byte[] signature = keyManager.sign(message.getBytesToSign());
        message.setSignature(signature);
        perfectLinks.sendMessage(message);
    }

    /* APL-Send with string payload */
    public void send(int destNodeId, MessageType type, String payload) {
        send(destNodeId, type, payload.getBytes());
    }

    /* Send authenticated message (full control) */
    public void sendMessage(int destNodeId, Message message) {
        Message toSend = message;
        if (message.getMessageId() <= 0) {
            long msgId = perfectLinks.nextMessageId();
            toSend = new Message(
                nodeId,
                destNodeId,
                msgId,
                message.getType(),
                message.getPayload()
            );
        }
        byte[] signature = keyManager.sign(toSend.getBytesToSign());
        toSend.setSignature(signature);
        perfectLinks.sendMessage(toSend);
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
        boolean senderIsNode = keyManager.hasPublicKey(senderId);

        if (senderIsNode) {
            if (message.getSignature() == null) {
                System.err.println(
                    "[APL] Missing signature from node " +
                        senderId +
                        ", dropping message"
                );
                return;
            }
            byte[] dataToVerify = message.getBytesToSign();
            boolean valid = keyManager.verify(
                senderId,
                dataToVerify,
                message.getSignature()
            );
            if (!valid) {
                System.err.println(
                    "[APL] Invalid signature from node " +
                        senderId +
                        ", dropping message"
                );
                return;
            }
        } else {
            // Non-member senders are only expected for client traffic.
            if (
                message.getType() != MessageType.CLIENT_REQUEST &&
                message.getType() != MessageType.CLIENT_REPLY
            ) {
                System.err.println(
                    "[APL] Unknown sender " +
                        senderId +
                        " with invalid type " +
                        message.getType()
                );
                return;
            }
        }

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
