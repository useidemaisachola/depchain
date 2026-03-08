package depchain.consensus;

import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class QuorumCertificate implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ConsensusPhase phase;
    private final int view;
    private final String blockHash;
    private final Map<Integer, byte[]> signatures;

    public QuorumCertificate(ConsensusPhase phase, int view, String blockHash, Map<Integer, byte[]> signatures) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.view = view;
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.signatures = new HashMap<>(Objects.requireNonNull(signatures, "signatures"));
    }

    public static QuorumCertificate fromVotes(ConsensusPhase phase, int view, String blockHash,
                                              Collection<ConsensusVote> votes) {
        Map<Integer, byte[]> signatures = new HashMap<>();
        for (ConsensusVote vote : votes) {
            if (vote.getPhase() != phase) {
                continue;
            }
            if (vote.getView() != view) {
                continue;
            }
            if (!blockHash.equals(vote.getBlockHash())) {
                continue;
            }
            signatures.put(vote.getVoterId(), vote.getSignature());
        }
        return new QuorumCertificate(phase, view, blockHash, signatures);
    }

    public boolean hasQuorum() {
        return NetworkConfig.isQuorum(signatures.size());
    }

    public boolean verify(KeyManager keyManager) {
        if (!hasQuorum()) {
            return false;
        }
        byte[] bytesToVerify = ConsensusVote.bytesToSign(phase, view, blockHash);
        for (Map.Entry<Integer, byte[]> entry : signatures.entrySet()) {
            if (!keyManager.verify(entry.getKey(), bytesToVerify, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public byte[] serialize() {
        return SerializationUtils.serialize(this);
    }

    public static QuorumCertificate deserialize(byte[] data) {
        return SerializationUtils.deserialize(data, QuorumCertificate.class);
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

    public Map<Integer, byte[]> getSignatures() {
        return Collections.unmodifiableMap(signatures);
    }
}
