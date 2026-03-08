package depchain.consensus;

import java.io.Serializable;

public class NewViewPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int newView;
    private final QuorumCertificate highQc;

    public NewViewPayload(int newView, QuorumCertificate highQc) {
        this.newView = newView;
        this.highQc = highQc;
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static NewViewPayload deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, NewViewPayload.class);
    }

    public int getNewView() {
        return newView;
    }

    public QuorumCertificate getHighQc() {
        return highQc;
    }
}
