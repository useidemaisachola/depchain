package depchain.client;

import depchain.consensus.SerializationUtils;

import java.io.Serializable;

public class ClientReply implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String requestId;
    private final boolean success;
    private final String message;
    private final int responderNodeId;
    private final int view;

    public ClientReply(String requestId, boolean success, String message, int responderNodeId, int view) {
        this.requestId = requestId;
        this.success = success;
        this.message = message;
        this.responderNodeId = responderNodeId;
        this.view = view;
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static ClientReply deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, ClientReply.class);
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public int getResponderNodeId() {
        return responderNodeId;
    }

    public int getView() {
        return view;
    }
}
