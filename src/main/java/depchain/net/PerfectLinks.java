package depchain.net;

import depchain.net.Message;
import depchain.net.MessageType;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Perfect Links (PL)
 *
 * Built on top of Stubborn Links.
 * Properties:
 * - PL1 (Reliable Delivery): If a correct process sends a message to a correct process,
 *   the message will eventually be delivered
 * - PL2 (No Duplication): A message is delivered at most once
 * - PL3 (No Creation): Only messages that were sent can be delivered
 *
 * Implementation:
 * - Uses ACKs to stop retransmission
 * - Keeps track of delivered messages to filter duplicates
 */
public class PerfectLinks implements StubbornLinks.Listener {

    public interface Listener {
        void onPLDeliver(int senderId, Message message);
    }

    private final StubbornLinks stubbornLinks;
    private final Listener listener;
    private final int nodeId;

    // Track delivered messages to avoid duplicates
    // Key: senderId + "-" + messageId
    private final Set<String> delivered;

    // Message ID counter for this node
    private final AtomicLong messageIdCounter;
    private final Set<Integer> knownNodeIds;

    public PerfectLinks(
        int nodeId,
        int port,
        Listener listener,
        Map<Integer, FairLossLinks.NodeAddress> nodeAddresses
    ) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.stubbornLinks = new StubbornLinks(
            nodeId,
            port,
            this,
            nodeAddresses
        );
        this.delivered = ConcurrentHashMap.newKeySet();
        this.messageIdCounter = new AtomicLong(0);
        this.knownNodeIds = new HashSet<>(nodeAddresses.keySet());
    }

    public void start() {
        stubbornLinks.start();
        System.out.println("[PL] Node " + nodeId + " started");
    }

    public void stop() {
        stubbornLinks.stop();
        delivered.clear();
    }

    /* PL-Send: Send a message reliably (exactly once delivery) */
    public void send(int destNodeId, MessageType type, byte[] payload) {
        long msgId = nextMessageId();
        Message message = new Message(nodeId, destNodeId, msgId, type, payload);
        stubbornLinks.send(destNodeId, message);
    }

    /* PL-Send with string payload */
    public void send(int destNodeId, MessageType type, String payload) {
        send(destNodeId, type, payload.getBytes());
    }

    public void sendMessage(Message message) {
        if (message.getSenderId() != nodeId) {
            throw new IllegalArgumentException(
                "Message sender must be this node"
            );
        }
        if (message.getMessageId() <= 0) {
            throw new IllegalArgumentException("Message ID must be positive");
        }
        stubbornLinks.send(message.getReceiverId(), message);
    }

    public long nextMessageId() {
        return messageIdCounter.incrementAndGet();
    }

    /* Broadcast to all known nodes */
    public void broadcast(MessageType type, byte[] payload) {
        for (int destination : knownNodeIds) {
            if (destination == nodeId) {
                continue;
            }
            send(destination, type, payload);
        }
    }

    @Override
    public void onSLDeliver(
        int senderId,
        Message message,
        InetAddress senderAddress,
        int senderPort
    ) {
        String messageKey = senderId + "-" + message.getMessageId();

        // Handle ACK messages
        if (message.getType() == MessageType.ACK) {
            // Stop retransmitting the original message
            // The ACK payload contains: originalSenderId + "-" + originalMessageId
            String ackPayload = message.getPayloadAsString();
            String[] parts = ackPayload.split("-");
            if (parts.length >= 2) {
                try {
                    int originalSender = Integer.parseInt(parts[0]);
                    long originalMsgId = Long.parseLong(parts[1]);
                    stubbornLinks.stopRetransmitting(
                        originalSender,
                        originalMsgId,
                        senderId
                    );
                } catch (NumberFormatException e) {
                    System.err.println(
                        "[PL] Invalid ACK payload: " + ackPayload
                    );
                }
            }
            return;
        }

        // Check for duplicates
        if (delivered.contains(messageKey)) {
            // Already delivered, send ACK again but don't deliver
            sendAck(
                senderAddress,
                senderPort,
                senderId,
                message.getMessageId()
            );
            return;
        }

        // First time receiving this message
        delivered.add(messageKey);

        // Send ACK
        sendAck(senderAddress, senderPort, senderId, message.getMessageId());

        // PL-Deliver
        listener.onPLDeliver(senderId, message);
    }

    private void sendAck(
        InetAddress address,
        int port,
        int originalSenderId,
        long originalMessageId
    ) {
        long ackMsgId = messageIdCounter.incrementAndGet();
        String ackPayload = originalSenderId + "-" + originalMessageId;
        Message ack = new Message(
            nodeId,
            originalSenderId,
            ackMsgId,
            MessageType.ACK,
            ackPayload
        );

        // Send ACK directly via FL (no retransmission needed for ACKs)
        stubbornLinks.sendOnce(address, port, ack);
    }

    public int getNodeId() {
        return nodeId;
    }

    public StubbornLinks getStubbornLinks() {
        return stubbornLinks;
    }
}
