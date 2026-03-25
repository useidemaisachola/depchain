package depchain.consensus;

import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class QuorumCertificate implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ConsensusPhase phase;
    private final int view;
    private final String blockHash;
    private final int creatorId;
    private final Map<Integer, byte[]> signatures;
    private final byte[] qcSignature;

    public QuorumCertificate(
        ConsensusPhase phase,
        int view,
        String blockHash,
        int creatorId,
        Map<Integer, byte[]> signatures,
        byte[] qcSignature
    ) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.view = view;
        this.blockHash = Objects.requireNonNull(blockHash, "blockHash");
        this.creatorId = creatorId;
        this.signatures = deepCopySignatures(signatures);
        this.qcSignature = Arrays.copyOf(
            Objects.requireNonNull(qcSignature, "qcSignature"),
            qcSignature.length
        );
    }

    public static QuorumCertificate fromVotes(
        ConsensusPhase phase,
        int view,
        String blockHash,
        int creatorId,
        KeyManager keyManager,
        Collection<ConsensusVote> votes
    ) {
        List<ConsensusVote> matchingVotes = votes
            .stream()
            .filter(vote -> vote.getPhase() == phase)
            .filter(vote -> vote.getView() == view)
            .filter(vote -> blockHash.equals(vote.getBlockHash()))
            .filter(vote -> vote.verify(keyManager))
            .sorted((left, right) ->
                Integer.compare(left.getVoterId(), right.getVoterId())
            )
            .limit(NetworkConfig.QUORUM_SIZE)
            .collect(Collectors.toList());

        if (!NetworkConfig.isQuorum(matchingVotes.size())) {
            throw new IllegalArgumentException(
                "Cannot build a QC without a quorum of valid threshold shares"
            );
        }

        Map<Integer, byte[]> signatures = new LinkedHashMap<>();
        for (ConsensusVote vote : matchingVotes) {
            signatures.put(vote.getVoterId(), vote.getSignature());
        }

        byte[] qcSignature = keyManager.combineThresholdShares(
            ConsensusVote.bytesToSign(phase, view, blockHash),
            signatures.values()
        );
        return new QuorumCertificate(
            phase,
            view,
            blockHash,
            creatorId,
            signatures,
            qcSignature
        );
    }

    public boolean hasQuorum() {
        return signatures.size() == NetworkConfig.QUORUM_SIZE;
    }

    public boolean verify(KeyManager keyManager) {
        if (!hasQuorum()) {
            return false;
        }
        if (creatorId < 0 || creatorId >= NetworkConfig.NUM_NODES) {
            return false;
        }
        if (creatorId != NetworkConfig.getLeader(view)) {
            return false;
        }
        byte[] bytesToVerify = ConsensusVote.bytesToSign(phase, view, blockHash);
        for (Map.Entry<Integer, byte[]> entry : signatures.entrySet()) {
            if (
                !keyManager.verifyThresholdShare(
                    entry.getKey(),
                    bytesToVerify,
                    entry.getValue()
                )
            ) {
                return false;
            }
        }
        try {
            byte[] recomputedSignature = keyManager.combineThresholdShares(
                bytesToVerify,
                signatures.values()
            );
            return (
                Arrays.equals(
                    keyManager.normalizeThresholdSignature(qcSignature),
                    recomputedSignature
                ) &&
                keyManager.verifyThresholdSignature(bytesToVerify, qcSignature)
            );
        } catch (RuntimeException e) {
            return false;
        }
    }

    public byte[] bytesToSign() {
        return ConsensusVote.bytesToSign(phase, view, blockHash);
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

    public int getCreatorId() {
        return creatorId;
    }

    public Map<Integer, byte[]> getSignatures() {
        return Collections.unmodifiableMap(deepCopySignatures(signatures));
    }

    public byte[] getQcSignature() {
        return Arrays.copyOf(qcSignature, qcSignature.length);
    }

    private static Map<Integer, byte[]> deepCopySignatures(
        Map<Integer, byte[]> signatures
    ) {
        Objects.requireNonNull(signatures, "signatures");
        Map<Integer, byte[]> copied = new HashMap<>();
        for (Map.Entry<Integer, byte[]> entry : signatures.entrySet()) {
            copied.put(
                entry.getKey(),
                Arrays.copyOf(
                    Objects.requireNonNull(entry.getValue(), "signature"),
                    entry.getValue().length
                )
            );
        }
        return copied;
    }
}
