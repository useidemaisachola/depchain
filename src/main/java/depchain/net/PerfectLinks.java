package depchain.net;

import depchain.net.Message;
import depchain.net.MessageType;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PerfectLinks implements StubbornLinks.Listener {

    public interface Listener {
        void onPLDeliver(int senderId, Message message);
    }

    private final StubbornLinks stubbornLinks;
    private final Listener listener;
    private final int nodeId;

    private final Set<String> delivered;

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
        System.out.println("[PL] Node " + nodeId + " up");
    }

    public void stop() {
        stubbornLinks.stop();
        delivered.clear();
    }

    public void send(int destNodeId, MessageType type, byte[] payload) {
        long msgId = nextMessageId();
        Message message = new Message(nodeId, destNodeId, msgId, type, payload);
        stubbornLinks.send(destNodeId, message);
    }

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

        if (message.getType() == MessageType.ACK) {
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
                        "[PL] ack payload looked weird: " + ackPayload
                    );
                }
            }
            return;
        }

        if (delivered.contains(messageKey)) {
            sendAck(
                senderAddress,
                senderPort,
                senderId,
                message.getMessageId()
            );
            return;
        }

        delivered.add(messageKey);

        sendAck(senderAddress, senderPort, senderId, message.getMessageId());

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

        stubbornLinks.sendOnce(address, port, ack);
    }

    public int getNodeId() {
        return nodeId;
    }

    public StubbornLinks getStubbornLinks() {
        return stubbornLinks;
    }
}
