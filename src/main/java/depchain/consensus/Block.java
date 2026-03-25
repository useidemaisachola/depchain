package depchain.consensus;

import depchain.crypto.CryptoUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class Block implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String hash;
    private final String parentHash;
    private final int height;
    private final int view;
    private final int proposerId;
    private final String requestId;
    private final int clientId;
    private final String clientHost;
    private final int clientPort;
    private final String data;
    private final long timestamp;

    private Block(String hash,
                  String parentHash,
                  int height,
                  int view,
                  int proposerId,
                  String requestId,
                  int clientId,
                  String clientHost,
                  int clientPort,
                  String data,
                  long timestamp) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.parentHash = Objects.requireNonNull(parentHash, "parentHash");
        this.height = height;
        this.view = view;
        this.proposerId = proposerId;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.clientId = clientId;
        this.clientHost = Objects.requireNonNull(clientHost, "clientHost");
        this.clientPort = clientPort;
        this.data = Objects.requireNonNull(data, "data");
        this.timestamp = timestamp;
    }

    public static Block create(String parentHash,
                               int height,
                               int view,
                               int proposerId,
                               String requestId,
                               int clientId,
                               String clientHost,
                               int clientPort,
                               String data,
                               long timestamp) {
        String computedHash = computeHash(
            parentHash, height, view, proposerId, requestId, clientId, clientHost, clientPort, data, timestamp
        );
        return new Block(
            computedHash,
            parentHash,
            height,
            view,
            proposerId,
            requestId,
            clientId,
            clientHost,
            clientPort,
            data,
            timestamp
        );
    }

    private static String computeHash(String parentHash,
                                      int height,
                                      int view,
                                      int proposerId,
                                      String requestId,
                                      int clientId,
                                      String clientHost,
                                      int clientPort,
                                      String data,
                                      long timestamp) {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeUTF(parentHash);
            dos.writeInt(height);
            dos.writeInt(view);
            dos.writeInt(proposerId);
            dos.writeUTF(requestId);
            dos.writeInt(clientId);
            dos.writeUTF(clientHost);
            dos.writeInt(clientPort);
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(dataBytes.length);
            dos.write(dataBytes);
            dos.writeLong(timestamp);
            return CryptoUtils.bytesToHex(CryptoUtils.hash(bos.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to compute block hash", e);
        }
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static Block deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, Block.class);
    }

    public String getHash() {
        return hash;
    }

    public boolean hasValidHash() {
        return hash.equals(
            computeHash(
                parentHash,
                height,
                view,
                proposerId,
                requestId,
                clientId,
                clientHost,
                clientPort,
                data,
                timestamp
            )
        );
    }

    public String getParentHash() {
        return parentHash;
    }

    public int getHeight() {
        return height;
    }

    public int getView() {
        return view;
    }

    public int getProposerId() {
        return proposerId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getClientId() {
        return clientId;
    }

    public String getClientHost() {
        return clientHost;
    }

    public int getClientPort() {
        return clientPort;
    }

    public String getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
