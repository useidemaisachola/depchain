package depchain.consensus;

import java.io.Serializable;
import java.util.Objects;

public class DecidePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int view;
    private final String blockHash;
    private final QuorumCertificate commitQc;

    public DecidePayload(int view, String blockHash, QuorumCertificate commitQc) {
        this.view = view;
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.commitQc = Objects.requireNonNull(commitQc, "commitQc");
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static DecidePayload deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, DecidePayload.class);
    }

    public int getView() {
        return view;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public QuorumCertificate getCommitQc() {
        return commitQc;
    }
}
