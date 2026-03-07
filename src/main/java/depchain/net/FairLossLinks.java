package depchain.net;

import depchain.net.Message;
import depchain.net.UdpTransport;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fair Loss Links (FL)
 * 
 * This is the bottom layer, directly above UDP.
 * Properties:
 * - FL1 (Fair Loss): If a message is sent infinitely often, it will eventually be delivered
 * - FL2 (Finite Duplication): A message is delivered at most a finite number of times
 * - FL3 (No Creation): Only messages that were sent can be delivered
 *
 */
public class FairLossLinks {
    
    public interface Listener {
        void onFLDeliver(int senderId, Message message, InetAddress senderAddress, int senderPort);
    }

    private final UdpTransport transport;
    private final Listener listener;
    private final int nodeId;
    private final Map<Integer, NodeAddress> nodeAddresses;

    public static class NodeAddress {
        public final String host;
        public final int port;
        
        public NodeAddress(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public FairLossLinks(int nodeId, int port, Listener listener, Map<Integer, NodeAddress> nodeAddresses) {
        this.nodeId = nodeId;
        this.listener = listener;
        this.nodeAddresses = new ConcurrentHashMap<>(nodeAddresses);
        
        this.transport = new UdpTransport(port, 65536, this::handlePacket);
    }

    public void start() {
        transport.start();
        System.out.println("[FL] Node " + nodeId + " started on port " + transport.getPort());
    }

    public void stop() {
        transport.stop();
    }

    /* FL-Send: Send a message to a destination node. The message may or may not arrive (fair loss property) */
    public void send(int destNodeId, Message message) {
        NodeAddress dest = nodeAddresses.get(destNodeId);
        if (dest == null) {
            System.err.println("[FL] Unknown destination node: " + destNodeId);
            return;
        }
        
        byte[] data = message.serialize();
        transport.send(data, dest.host, dest.port);
    }

    /* FL-Send to a specific address (for replies) */
    public void send(InetAddress address, int port, Message message) {
        byte[] data = message.serialize();
        transport.send(data, address, port);
    }

    private void handlePacket(DatagramPacket packet) {
        try {
            byte[] data = packet.getData();
            int length = packet.getLength();
            byte[] messageData = new byte[length];
            System.arraycopy(data, 0, messageData, 0, length);
            
            Message message = Message.deserialize(messageData);
            
            // FL-Deliver
            listener.onFLDeliver(message.getSenderId(), message, packet.getAddress(), packet.getPort());
            
        } catch (Exception e) {
            System.err.println("[FL] Failed to process packet: " + e.getMessage());
        }
    }

    public int getNodeId() {
        return nodeId;
    }
}
