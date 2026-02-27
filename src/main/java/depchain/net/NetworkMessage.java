package depchain.net;

public class NetworkMessage {

    private String message;
    private MessageType type

    public NetworkMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return this.message;
    }
}
