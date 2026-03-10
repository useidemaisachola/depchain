package depchain.net;

import depchain.crypto.KeyManager;
import depchain.net.Message;
import depchain.net.MessageType;
import java.util.Map;

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
}
