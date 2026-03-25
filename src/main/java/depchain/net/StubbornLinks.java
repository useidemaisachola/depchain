package depchain.net;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.*;

public class StubbornLinks implements FairLossLinks.Listener {

    public interface Listener {
        void onSLDeliver(
            int senderId,
            Message message,
            InetAddress senderAddress,
            int senderPort
        );
    }

    private static final long RETRANSMIT_INTERVAL_MS = 500;

    private final FairLossLinks fairLossLinks;
    private final Listener listener;
    private final int nodeId;

    private final ConcurrentHashMap<String, PendingMessage> pendingMessages;

    private final ScheduledExecutorService retransmitScheduler;
    private volatile boolean running = false;

    private static class PendingMessage {

        final int destNodeId;
        final Message message;

        PendingMessage(int destNodeId, Message message) {
            this.destNodeId = destNodeId;
            this.message = message;
        }
    }

    public StubbornLinks(
        int nodeId,
        int port,
        Listener listener,
        Map<Integer, FairLossLinks.NodeAddress> nodeAddresses
    ) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.fairLossLinks = new FairLossLinks(
            nodeId,
            port,
            this,
            nodeAddresses
        );
        this.pendingMessages = new ConcurrentHashMap<>();
        this.retransmitScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "SL-retransmit-" + nodeId);
                t.setDaemon(true);
                return t;
            }
        );
    }

    public void start() {
        running = true;
        fairLossLinks.start();

        retransmitScheduler.scheduleAtFixedRate(
            this::retransmitAll,
            RETRANSMIT_INTERVAL_MS,
            RETRANSMIT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        System.out.println("[SL] Node " + nodeId + " up");
    }

    public void stop() {
        running = false;
        retransmitScheduler.shutdown();
        fairLossLinks.stop();
        pendingMessages.clear();
    }

    public void send(int destNodeId, Message message) {
        String key = makeKey(
            message.getSenderId(),
            message.getMessageId(),
            destNodeId
        );
        pendingMessages.put(key, new PendingMessage(destNodeId, message));

        fairLossLinks.send(destNodeId, message);
    }

    public void stopRetransmitting(
        int senderId,
        long messageId,
        int destNodeId
    ) {
        String key = makeKey(senderId, messageId, destNodeId);
        pendingMessages.remove(key);
    }

    public void sendOnce(InetAddress address, int port, Message message) {
        fairLossLinks.send(address, port, message);
    }

    private void retransmitAll() {
        if (!running) return;

        for (PendingMessage pending : pendingMessages.values()) {
            fairLossLinks.send(pending.destNodeId, pending.message);
        }
    }

    private String makeKey(int senderId, long messageId, int destNodeId) {
        return senderId + "-" + messageId + "-" + destNodeId;
    }

    @Override
    public void onFLDeliver(
        int senderId,
        Message message,
        InetAddress senderAddress,
        int senderPort
    ) {
        listener.onSLDeliver(senderId, message, senderAddress, senderPort);
    }

    public FairLossLinks getFairLossLinks() {
        return fairLossLinks;
    }

    public int getNodeId() {
        return nodeId;
    }

    public int getPendingMessageCount() {
        return pendingMessages.size();
    }
}
