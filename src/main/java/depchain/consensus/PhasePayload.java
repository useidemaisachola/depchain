package depchain.consensus;

import java.io.Serializable;
import java.util.Objects;

public class PhasePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int view;
    private final String blockHash;
    private final Block block;
    private final QuorumCertificate quorumCertificate;

    public PhasePayload(
        int view,
        String blockHash,
        Block block,
        QuorumCertificate quorumCertificate
    ) {
        this.view = view;
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.block = block;
        this.quorumCertificate = Objects.requireNonNull(quorumCertificate, "quorumCertificate");
    }

    public PhasePayload(int view, String blockHash, QuorumCertificate quorumCertificate) {
        this(view, blockHash, null, quorumCertificate);
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static PhasePayload deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, PhasePayload.class);
    }

    public int getView() {
        return view;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public Block getBlock() {
        return block;
    }

    public QuorumCertificate getQuorumCertificate() {
        return quorumCertificate;
    }
}
