package depchain.consensus;

import depchain.crypto.KeyManager;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

public class ConsensusVote implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ConsensusPhase phase;
    private final int view;
    private final String blockHash;
    private final int voterId;
    private final byte[] signature;

    public ConsensusVote(ConsensusPhase phase, int view, String blockHash, int voterId, byte[] signature) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.view = view;
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.voterId = voterId;
        this.signature = Arrays.copyOf(Objects.requireNonNull(signature, "signature"), signature.length);
    }

    public static ConsensusVote create(ConsensusPhase phase, int view, String blockHash, int voterId, KeyManager keyManager) {
        byte[] toSign = bytesToSign(phase, view, blockHash);
        byte[] signature = keyManager.signThresholdShare(toSign);
        return new ConsensusVote(phase, view, blockHash, voterId, signature);
    }

    public boolean verify(KeyManager keyManager) {
        byte[] toVerify = bytesToSign(phase, view, blockHash);
        return keyManager.verifyThresholdShare(voterId, toVerify, signature);
    }

    public static byte[] bytesToSign(ConsensusPhase phase, int view, String blockHash) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeUTF(phase.name());
            dos.writeInt(view);
            dos.writeUTF(blockHash);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build vote bytes", e);
        }
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static ConsensusVote deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, ConsensusVote.class);
    }

    public ConsensusPhase getPhase() {
        return phase;
    }

    public int getView() {
        return view;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public int getVoterId() {
        return voterId;
    }

    public byte[] getSignature() {
        return Arrays.copyOf(signature, signature.length);
    }
}
