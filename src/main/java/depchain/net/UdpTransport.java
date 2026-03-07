package depchain.net;

import java.io.*;
import java.net.*;

public class UdpTransport {

    public interface Handler {
        void handle(DatagramPacket packet);
    }

    private final int bindPort;
    private final int maxPacketBytes;
    private final Handler handler;
    private DatagramSocket socket;
    private Thread recvThread;
    private volatile boolean running = false;

    public UdpTransport(int bindPort, int maxPacketBytes, Handler handler) {
        this.bindPort = bindPort;
        this.maxPacketBytes = maxPacketBytes;
        this.handler = handler;
        this.running = false;
        this.socket = null;
        this.recvThread = null;
    }

    public void start() {
        if (running) return;
        try {
            this.socket = new DatagramSocket(this.bindPort);
            this.running = true;
            this.recvThread = new Thread(
                this::receiveLoop,
                "udp-transport-" + this.bindPort
            );
            recvThread.setDaemon(true);
            recvThread.start();
            System.out.println("[UdpTransport] Started on port " + bindPort);
        } catch (SocketException e) {
            running = false;
            throw new RuntimeException(
                "Failed to open UDP port " + bindPort,
                e
            );
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (recvThread != null) {
            recvThread.interrupt();
        }
    }

    public void send(byte[] data, InetAddress address, int port) {
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("Socket not started");
        }
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[UdpTransport] Send failed: " + e.getMessage());
        }
    }

    public void send(byte[] data, String host, int port) {
        try {
            send(data, InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            System.err.println("[UdpTransport] Unknown host: " + host);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[this.maxPacketBytes];
        while (this.running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                // Create a copy of the data for the handler
                byte[] receivedData = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), receivedData, 0, packet.getLength());
                DatagramPacket copy = new DatagramPacket(
                    receivedData, receivedData.length,
                    packet.getAddress(), packet.getPort()
                );
                handler.handle(copy);
            } catch (SocketException e) {
                if (this.running) {
                    System.err.println("[UdpTransport] Socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (this.running) {
                    System.err.println("[UdpTransport] IO error: " + e.getMessage());
                }
                break;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return bindPort;
    }

    @Override
    public String toString() {
        return "UdpTransport{port=" + bindPort + ", running=" + running + "}";
    }
}
