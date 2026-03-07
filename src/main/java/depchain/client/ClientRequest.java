package depchain.client;

import java.io.*;

/*Represents a client request to append data to the blockchain.*/
public class ClientRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final int clientId;
    private final String data;
    private final long timestamp;

    public ClientRequest(int clientId, String data, long timestamp) {
        this.clientId = clientId;
        this.data = data;
        this.timestamp = timestamp;
    }

    public int getClientId() { return clientId; }
    public String getData() { return data; }
    public long getTimestamp() { return timestamp; }

    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize ClientRequest", e);
        }
    }

    public static ClientRequest deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (ClientRequest) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize ClientRequest", e);
        }
    }

    @Override
    public String toString() {
        return "ClientRequest{clientId=" + clientId + ", data='" + data + "', timestamp=" + timestamp + "}";
    }
}
