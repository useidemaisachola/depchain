package depchain.node;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class Node {

    private final int port;
    private final String nodeId;
    private volatile boolean running = false;

    public Node(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
    }

    @Override
    public String toString() {
        return nodeId + ":" + port;
    }

    public void start() {
        running = true;

        try (DatagramSocket socket = new DatagramSocket(this.port)) {
            System.out.println(
                "[" + nodeId + "] Listening UDP on port " + port
            );

            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length
                );
                socket.receive(packet); // blocks until message arrives

                String message = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8
                );

                System.out.println(
                    "[" +
                        nodeId +
                        "] Received: " +
                        message +
                        " from " +
                        packet.getAddress() +
                        ":" +
                        packet.getPort()
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void send(String host, int destinationPort, String message) {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            InetAddress address = InetAddress.getByName(host);

            DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                address,
                destinationPort
            );

            socket.send(packet);

            System.out.println(
                "[" +
                    nodeId +
                    "] Sent: " +
                    message +
                    " to " +
                    host +
                    ":" +
                    destinationPort
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        running = false;
    }
}
