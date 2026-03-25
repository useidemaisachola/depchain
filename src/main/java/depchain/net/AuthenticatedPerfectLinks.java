package depchain.net;

import depchain.crypto.KeyManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AuthenticatedPerfectLinks implements PerfectLinks.Listener {

    public interface Listener {
        void onAPLDeliver(int senderId, Message message);
    }

    private final PerfectLinks perfectLinks;
    private final Listener listener;
    private final int nodeId;
    private final KeyManager keyManager;
    private static final long REPLAY_WINDOW_SIZE = 4096L;
    // Per-sender anti-replay window: allow bounded out-of-order delivery while rejecting duplicates and stale replays.
    private final Map<Integer, ReplayWindow> senderReplayWindows =
        new ConcurrentHashMap<>();

    private static final class ReplayWindow {
        private long highestSeen;
        private final Set<Long> seenMessageIds;

        private ReplayWindow() {
            this(0L, new HashSet<>());
        }

        private ReplayWindow(long highestSeen, Set<Long> seenMessageIds) {
            this.highestSeen = Math.max(0L, highestSeen);
            this.seenMessageIds = new HashSet<>(seenMessageIds);
            prune();
        }

        private boolean shouldReject(long messageId) {
            if (messageId <= 0) {
                return true;
            }
            if (seenMessageIds.contains(messageId)) {
                return true;
            }
            long minAccepted = Math.max(1L, highestSeen - REPLAY_WINDOW_SIZE + 1);
            return highestSeen > 0 && messageId < minAccepted;
        }

        private void record(long messageId) {
            highestSeen = Math.max(highestSeen, messageId);
            seenMessageIds.add(messageId);
            prune();
        }

        private ReplayWindowState snapshot() {
            return new ReplayWindowState(highestSeen, seenMessageIds);
        }

        private void prune() {
            long minAccepted = Math.max(1L, highestSeen - REPLAY_WINDOW_SIZE + 1);
            seenMessageIds.removeIf(id -> id < minAccepted);
        }
    }

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
        this.perfectLinks = new PerfectLinks(
            nodeId,
            port,
            this,
            nodeAddresses,
            keyManager
        );
    }

    public void start() {
        perfectLinks.start();
        System.out.println("[APL] Node " + nodeId + " up w/ auth stuff");
    }

    public void stop() {
        perfectLinks.stop();
    }

    public void send(int destNodeId, MessageType type, byte[] payload) {
        long msgId = perfectLinks.nextMessageId();
        Message message = new Message(nodeId, destNodeId, msgId, type, payload);
        byte[] signature = keyManager.sign(message.getBytesToSign());
        message.setSignature(signature);
        perfectLinks.sendMessage(message);
    }

    public void send(int destNodeId, MessageType type, String payload) {
        send(destNodeId, type, payload.getBytes());
    }

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
                    "[APL] Node " + senderId + " sent no sig, dropping it"
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
                    "[APL] Node " + senderId + " sig looks bad, dropping it"
                );
                return;
            }
            long msgId = message.getMessageId();
            ReplayWindow replayWindow = senderReplayWindows.computeIfAbsent(
                senderId,
                ignored -> new ReplayWindow()
            );
            if (replayWindow.shouldReject(msgId)) {
                System.err.println(
                    "[APL] Replay from node " + senderId +
                    " (msgId=" + msgId + "), dropping"
                );
                return;
            }
            replayWindow.record(msgId);
        } else {
            if (
                message.getType() != MessageType.CLIENT_REQUEST &&
                message.getType() != MessageType.CLIENT_REPLY
            ) {
                System.err.println(
                    "[APL] Dont know sender " +
                        senderId +
                        " with weird type " +
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

    public Map<Integer, ReplayWindowState> snapshotReplayWindows() {
        Map<Integer, ReplayWindowState> snapshot = new HashMap<>();
        for (Map.Entry<Integer, ReplayWindow> entry : senderReplayWindows.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().snapshot());
        }
        return snapshot;
    }

    public void restoreReplayWindows(Map<Integer, ReplayWindowState> states) {
        senderReplayWindows.clear();
        if (states != null) {
            for (Map.Entry<Integer, ReplayWindowState> entry : states.entrySet()) {
                ReplayWindowState state = entry.getValue();
                if (state == null) {
                    continue;
                }
                senderReplayWindows.put(
                    entry.getKey(),
                    new ReplayWindow(
                        state.getHighestSeen(),
                        state.getSeenMessageIds()
                    )
                );
            }
        }
    }

    public long getCurrentMessageId() {
        return perfectLinks.getCurrentMessageId();
    }

    public void restoreCurrentMessageId(long lastUsedMessageId) {
        perfectLinks.restoreCurrentMessageId(lastUsedMessageId);
    }
}
