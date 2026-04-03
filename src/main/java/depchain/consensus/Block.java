package depchain.consensus;

import depchain.client.ClientRequest;
import depchain.crypto.CryptoUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Block implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String hash;
    private final String parentHash;
    private final int height;
    private final int view;
    private final int proposerId;
    private final List<ClientRequest> requests;
    private final long timestamp;

    private Block(String hash,
                  String parentHash,
                  int height,
                  int view,
                  int proposerId,
                  List<ClientRequest> requests,
                  long timestamp) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.parentHash = Objects.requireNonNull(parentHash, "parentHash");
        this.height = height;
        this.view = view;
        this.proposerId = proposerId;
        Objects.requireNonNull(requests, "requests");
        this.requests = Collections.unmodifiableList(new ArrayList<>(requests));
        this.timestamp = timestamp;
    }

    public static Block create(String parentHash,
                               int height,
                               int view,
                               int proposerId,
                               List<ClientRequest> requests,
                               long timestamp) {
        String computedHash = computeHash(
            parentHash, height, view, proposerId, requests, timestamp
        );
        return new Block(
            computedHash,
            parentHash,
            height,
            view,
            proposerId,
            requests,
            timestamp
        );
    }

    private static String computeHash(String parentHash,
                                      int height,
                                      int view,
                                      int proposerId,
                                      List<ClientRequest> requests,
                                      long timestamp) {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeUTF(parentHash);
            dos.writeInt(height);
            dos.writeInt(view);
            dos.writeInt(proposerId);

            Objects.requireNonNull(requests, "requests");
            dos.writeInt(requests.size());
            for (ClientRequest request : requests) {
                dos.writeInt(request.getClientId());
                dos.writeUTF(request.getRequestId());
                byte[] dataBytes = request.getData().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(dataBytes.length);
                dos.write(dataBytes);
                dos.writeLong(request.getTimestamp());
                dos.writeUTF(request.getReplyHost());
                dos.writeInt(request.getReplyPort());
                byte[] clientKey = request.getClientPublicKey();
                dos.writeInt(clientKey.length);
                dos.write(clientKey);
                byte[] signature = request.getSignature();
                if (signature == null) {
                    dos.writeInt(-1);
                } else {
                    dos.writeInt(signature.length);
                    dos.write(signature);
                }
            }

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
                requests,
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

    public List<ClientRequest> getRequests() {
        return requests;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
