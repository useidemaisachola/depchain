package depchain.client;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;

/*Represents a client request to append data to the blockchain.*/
public class ClientRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int clientId;
    private final String requestId;
    private final String data;
    private final long timestamp;
    private final String replyHost;
    private final int replyPort;
    private final byte[] clientPublicKey;
    private final byte[] signature;

    public ClientRequest(
        int clientId,
        String requestId,
        String data,
        long timestamp,
        String replyHost,
        int replyPort,
        byte[] clientPublicKey,
        byte[] signature
    ) {
        this.clientId = clientId;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.data = Objects.requireNonNull(data, "data");
        this.timestamp = timestamp;
        this.replyHost = Objects.requireNonNull(replyHost, "replyHost");
        this.replyPort = replyPort;
        this.clientPublicKey = Arrays.copyOf(
            Objects.requireNonNull(clientPublicKey, "clientPublicKey"),
            clientPublicKey.length
        );
        this.signature =
            signature == null
                ? null
                : Arrays.copyOf(signature, signature.length);
    }

    public ClientRequest withSignature(byte[] newSignature) {
        return new ClientRequest(
            clientId,
            requestId,
            data,
            timestamp,
            replyHost,
            replyPort,
            clientPublicKey,
            newSignature
        );
    }

    public byte[] bytesToSign() {
        try (
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos)
        ) {
            dos.writeInt(clientId);
            dos.writeUTF(requestId);
            dos.writeUTF(data);
            dos.writeLong(timestamp);
            dos.writeUTF(replyHost);
            dos.writeInt(replyPort);
            dos.writeInt(clientPublicKey.length);
            dos.write(clientPublicKey);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to build request bytes to sign",
                e
            );
        }
    }

    public int getClientId() {
        return clientId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getReplyHost() {
        return replyHost;
    }

    public int getReplyPort() {
        return replyPort;
    }

    public byte[] getClientPublicKey() {
        return Arrays.copyOf(clientPublicKey, clientPublicKey.length);
    }

    public byte[] getSignature() {
        return signature == null
            ? null
            : Arrays.copyOf(signature, signature.length);
    }

    public byte[] serialize() {
        try (
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos)
        ) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize ClientRequest", e);
        }
    }

    public static ClientRequest deserialize(byte[] data) {
        try (
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            ObjectInputStream ois = new ObjectInputStream(bis)
        ) {
            return (ClientRequest) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(
                "Failed to deserialize ClientRequest",
                e
            );
        }
    }

    @Override
    public String toString() {
        return (
            "ClientRequest{clientId=" +
            clientId +
            ", requestId='" +
            requestId +
            "', data='" +
            data +
            "'}"
        );
    }
}
