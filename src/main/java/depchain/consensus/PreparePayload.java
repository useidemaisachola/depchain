package depchain.consensus;

import java.io.Serializable;
import java.util.Objects;

public class PreparePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int view;
    private final Block block;
    private final QuorumCertificate justifyQc;

    public PreparePayload(int view, Block block, QuorumCertificate justifyQc) {
        this.view = view;
        this.block = Objects.requireNonNull(block, "block");
        this.justifyQc = justifyQc;
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static PreparePayload deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, PreparePayload.class);
    }

    public int getView() {
        return view;
    }

    public Block getBlock() {
        return block;
    }

    public QuorumCertificate getJustifyQc() {
        return justifyQc;
    }
}
