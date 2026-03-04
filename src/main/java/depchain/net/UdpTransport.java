package depchain.net;

import java.io.*;
import java.net.*;
import java.util.UUID;

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
        } catch (SocketException e) {
            running = false;
            throw new RuntimeException(
                "Failed to open UDP port " + bindPort,
                e
            );
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[this.maxPacketBytes];
        while (this.running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                handler.handle(packet);
            } catch (IOException e) {
                if (this.running) {
                    e.printStackTrace();
                }
                break;
            } catch (SocketException e) {
                if (this.running) {
                    e.printStackTrace();
                }
                break;
            } catch (Exception e) {
                if (this.running) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    @Override
    public String toString() {
        return (
            "UdpTransport{" +
            "bindPort=" +
            bindPort +
            ", maxPacketBytes=" +
            maxPacketBytes +
            ", handler=" +
            handler +
            '}'
        );
    }
}
