package depchain.node;

public class Node {

    private int port;

    public Node(int port) {
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public String toString() {
        return Integer.toString(this.port);
    }

    public void start() {}
}
