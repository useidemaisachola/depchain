package depchain.node;

import depchain.net.Message;
import depchain.net.MessageType;
import depchain.net.UdpTransport;
import java.net.InetSocketAddress;
import java.util.UUID;

public class Node implements AutoCloseable {

    private final int port;
    private final String nodeId;

    private final UUID selfUuid;
    private UdpTransport transport;

    public Node(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
        this.transport = new UdpTransport(port, 1024, null);
        this.selfUuid = UUID.nameUUIDFromBytes(nodeId.getBytes());
    }

    public final UdpTransport getTransport() {
        return transport;
    }

    public final UUID getSelfUuid() {
        return selfUuid;
    }

    @Override
    public String toString() {
        return nodeId + ":" + port;
    }
}
