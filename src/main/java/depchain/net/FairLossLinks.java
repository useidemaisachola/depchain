package depchain.net;

import depchain.net.Message;
import depchain.net.UdpTransport;
import depchain.net.fault.NetworkFaultController;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FairLossLinks {

    public interface Listener {
        void onFLDeliver(
            int senderId,
            Message message,
            InetAddress senderAddress,
            int senderPort
        );
    }

    private final UdpTransport transport;
    private final Listener listener;
    private final int nodeId;
    private final Map<Integer, NodeAddress> nodeAddresses;
    private final ScheduledExecutorService faultScheduler;

    public static class NodeAddress {

        public final String host;
        public final int port;

        public NodeAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public FairLossLinks(
        int nodeId,
        int port,
        Listener listener,
        Map<Integer, NodeAddress> nodeAddresses
    ) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.nodeAddresses = new ConcurrentHashMap<>(nodeAddresses);
        this.faultScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FL-faults-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        this.transport = new UdpTransport(port, 65536, this::handlePacket);
    }

    public void start() {
        transport.start();
        System.out.println(
            "[FL] Node " + nodeId + " up on port " + transport.getPort()
        );
    }

    public void stop() {
        transport.stop();
        faultScheduler.shutdownNow();
    }

    public void send(int destNodeId, Message message) {
        NodeAddress dest = nodeAddresses.get(destNodeId);
        if (dest == null) {
            System.err.println("[FL] dont know dest node: " + destNodeId);
            return;
        }

        byte[] data = message.serialize();
        NetworkFaultController.SendPlan plan =
            NetworkFaultController.evaluateSend(nodeId, destNodeId, message);
        if (plan.isDrop()) {
            System.out.println(
                "[FL] fault thing dropped msg from " +
                    nodeId +
                    " to " +
                    destNodeId +
                    " type=" +
                    message.getType()
            );
            return;
        }

        for (int copy = 0; copy < plan.getCopies(); copy++) {
            byte[] payload = buildPayloadCopy(data, copy, plan.isCorrupt());
            long delay = plan.getDelayMs() + (copy * 5L);
            if (delay > 0) {
                faultScheduler.schedule(
                    () -> transport.send(payload, dest.host, dest.port),
                    delay,
                    TimeUnit.MILLISECONDS
                );
            } else {
                transport.send(payload, dest.host, dest.port);
            }
        }
    }

    public void send(InetAddress address, int port, Message message) {
        byte[] data = message.serialize();
        transport.send(data, address, port);
    }

    private byte[] buildPayloadCopy(
        byte[] original,
        int copyIndex,
        boolean corrupt
    ) {
        byte[] copy = java.util.Arrays.copyOf(original, original.length);
        if (corrupt && copy.length > 0) {
            int index = Math.min(copy.length - 1, copyIndex);
            copy[index] = (byte) (copy[index] ^ 0x7F);
        }
        return copy;
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            byte[] data = packet.getData();
            int length = packet.getLength();
            byte[] messageData = new byte[length];
            System.arraycopy(data, 0, messageData, 0, length);

            Message message = Message.deserialize(messageData);

            listener.onFLDeliver(
                message.getSenderId(),
                message,
                packet.getAddress(),
                packet.getPort()
            );
        } catch (Exception e) {
            System.err.println("[FL] packet parse went bad: " + e.getMessage());
        }
    }

    public int getNodeId() {
        return nodeId;
    }
}
