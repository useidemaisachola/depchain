package depchain.node;

import depchain.client.ClientReply;
import depchain.client.ClientRequest;
import depchain.client.EncryptedPayload;
import depchain.config.NetworkConfig;
import depchain.consensus.Block;
import depchain.consensus.ConsensusPhase;
import depchain.consensus.ConsensusVote;
import depchain.consensus.DecidePayload;
import depchain.consensus.NewViewPayload;
import depchain.consensus.PhasePayload;
import depchain.consensus.PreparePayload;
import depchain.consensus.QuorumCertificate;
import depchain.crypto.CryptoUtils;
import depchain.crypto.KeyManager;
import depchain.net.AuthenticatedPerfectLinks;
import depchain.net.FairLossLinks.NodeAddress;
import depchain.net.Message;
import depchain.net.MessageType;
import depchain.storage.NodePersistentState;
import depchain.storage.NodeStateStore;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Node implements AuthenticatedPerfectLinks.Listener, AutoCloseable {

    private static final String GENESIS_HASH = "GENESIS";
    private static final long VIEW_TIMEOUT_MS = 5000;

    private enum ConsensusStep {
        IDLE,
        PREPARE,
        PRE_COMMIT,
        COMMIT,
    }

    private final int nodeId;
    private final int port;
    private final KeyManager keyManager;
    private final AuthenticatedPerfectLinks apl;
    private final List<String> blockchain;
    private final Object lock;
    private final ByzantineBehavior byzantineBehavior;
    private final NodeStateStore stateStore;
    private final boolean persistenceEnabled;

    private final Queue<ClientRequest> pendingClientRequests;
    private final Set<String> pendingRequestIds;
    private final Set<String> decidedRequestIds;
    private final Set<String> repliedRequestIds;
    private final Map<Integer, byte[]> clientPublicKeys;

    private final Map<String, Block> blocksByHash;
    private final Set<String> committedBlockHashes;
    private final Map<String, Map<Integer, ConsensusVote>> prepareVotes;
    private final Map<String, Map<Integer, ConsensusVote>> preCommitVotes;
    private final Map<String, Map<Integer, ConsensusVote>> commitVotes;
    private final Map<Integer, Set<Integer>> newViewVotes;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timeoutTask;
    private volatile boolean running;

    private int currentView;
    private String lastCommittedHash;
    private int lastCommittedHeight;
    private String activeBlockHash;
    private ClientRequest activeClientRequest;
    private ConsensusStep activeStep;
    private QuorumCertificate highQc;
    private QuorumCertificate lockedQc;

    private NodeListener listener;

    public interface NodeListener {
        void onMessageReceived(int senderId, Message message);
        void onBlockAppended(String data);
    }

    public Node(int nodeId, KeyManager keyManager) {
        this(
            nodeId,
            keyManager,
            ByzantineBehavior.HONEST,
            NetworkConfig.STATE_DIRECTORY,
            true
        );
    }

    public Node(
        int nodeId,
        KeyManager keyManager,
        ByzantineBehavior byzantineBehavior
    ) {
        this(
            nodeId,
            keyManager,
            byzantineBehavior,
            NetworkConfig.STATE_DIRECTORY,
            true
        );
    }

    public Node(
        int nodeId,
        KeyManager keyManager,
        ByzantineBehavior byzantineBehavior,
        String stateDirectory,
        boolean persistenceEnabled
    ) {
        this.nodeId = nodeId;
        this.port = NetworkConfig.getNodePort(nodeId);
        this.keyManager = keyManager;
        this.blockchain = new CopyOnWriteArrayList<>();
        this.lock = new Object();
        this.byzantineBehavior = byzantineBehavior;
        this.persistenceEnabled = persistenceEnabled;
        this.stateStore = new NodeStateStore(nodeId, stateDirectory);

        this.pendingClientRequests = new ArrayDeque<>();
        this.pendingRequestIds = new HashSet<>();
        this.decidedRequestIds = new HashSet<>();
        this.repliedRequestIds = new HashSet<>();
        this.clientPublicKeys = new ConcurrentHashMap<>();

        this.blocksByHash = new HashMap<>();
        this.committedBlockHashes = new HashSet<>();
        this.prepareVotes = new HashMap<>();
        this.preCommitVotes = new HashMap<>();
        this.commitVotes = new HashMap<>();
        this.newViewVotes = new HashMap<>();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-" + nodeId + "-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.running = false;

        this.currentView = 0;
        this.lastCommittedHash = GENESIS_HASH;
        this.lastCommittedHeight = 0;
        this.activeBlockHash = null;
        this.activeClientRequest = null;
        this.activeStep = ConsensusStep.IDLE;
        this.highQc = null;
        this.lockedQc = null;

        Map<Integer, NodeAddress> nodeAddresses =
            NetworkConfig.getAllNodeAddresses();
        this.apl = new AuthenticatedPerfectLinks(
            nodeId,
            port,
            this,
            nodeAddresses,
            keyManager
        );
    }

    public void setListener(NodeListener listener) {
        this.listener = listener;
    }

    public void start() {
        synchronized (lock) {
            loadPersistentStateLocked();
            running = true;
        }
        apl.start();
        synchronized (lock) {
            scheduleViewTimeoutLocked();
        }
        System.out.println(
            "[Node " +
                nodeId +
                "] up on port " +
                port +
                " (leader for view " +
                currentView +
                " is " +
                NetworkConfig.getLeader(currentView) +
                ", behavior=" +
                byzantineBehavior +
                ")"
        );
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            if (timeoutTask != null) {
                timeoutTask.cancel(true);
                timeoutTask = null;
            }
        }
        apl.stop();
        synchronized (lock) {
            savePersistentStateLocked();
        }
        scheduler.shutdownNow();
        System.out.println("[Node " + nodeId + "] stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public void send(int destNodeId, MessageType type, String payload) {
        apl.send(destNodeId, type, payload);
    }

    public void broadcast(MessageType type, String payload) {
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            if (i != nodeId) {
                send(i, type, payload);
            }
        }
    }

    public void appendToBlockchain(String data) {
        blockchain.add(data);
        System.out.println("[Node " + nodeId + "] chain got: " + data);
        if (listener != null) {
            listener.onBlockAppended(data);
        }
    }

    public List<String> getBlockchain() {
        return Collections.unmodifiableList(blockchain);
    }

    public int getCurrentView() {
        synchronized (lock) {
            return currentView;
        }
    }

    @Override
    public void onAPLDeliver(int senderId, Message message) {
        if (byzantineBehavior == ByzantineBehavior.SILENT) {
            return;
        }

        switch (message.getType()) {
            case DATA:
                handleDataMessage(senderId, message);
                break;
            case CLIENT_REQUEST:
                handleClientRequest(senderId, message);
                break;
            case PREPARE:
                handlePrepare(senderId, message);
                break;
            case PREPARE_VOTE:
                handlePrepareVote(senderId, message);
                break;
            case PRE_COMMIT:
                handlePreCommit(senderId, message);
                break;
            case PRE_COMMIT_VOTE:
                handlePreCommitVote(senderId, message);
                break;
            case COMMIT:
                handleCommit(senderId, message);
                break;
            case COMMIT_VOTE:
                handleCommitVote(senderId, message);
                break;
            case DECIDE:
                handleDecide(senderId, message);
                break;
            case NEW_VIEW:
                handleNewView(senderId, message);
                break;
            default:
                System.out.println(
                    "[Node " +
                        nodeId +
                        "] ignored msg type " +
                        message.getType() +
                        " from " +
                        senderId
                );
        }

        if (listener != null) {
            listener.onMessageReceived(senderId, message);
        }
    }

    private void handleDataMessage(int senderId, Message message) {
        System.out.println(
            "[Node " +
                nodeId +
                "] got data from node " +
                senderId +
                ": " +
                message.getPayloadAsString()
        );
    }

    private void handleClientRequest(int senderId, Message message) {
        ClientRequest request = decodeClientRequest(senderId, message);
        if (request == null) {
            System.err.println(
                "[Node " +
                    nodeId +
                    "] couldnt read client req from sender " +
                    senderId
            );
            return;
        }
        if (!verifyClientRequest(request)) {
            System.err.println(
                "[Node " +
                    nodeId +
                    "] client req looks bad " +
                    request.getRequestId()
            );
            return;
        }

        synchronized (lock) {
            if (decidedRequestIds.contains(request.getRequestId())) {
                sendClientReply(request, true, "req was alrdy decided");
                return;
            }

            if (pendingRequestIds.add(request.getRequestId())) {
                pendingClientRequests.offer(request);
            }

            int leaderId = NetworkConfig.getLeader(currentView);
            if (nodeId != leaderId) {
                if (senderId != leaderId) {
                    apl.send(
                        leaderId,
                        MessageType.CLIENT_REQUEST,
                        request.serialize()
                    );
                }
                return;
            }

            tryStartConsensusLocked();
        }
    }

    private ClientRequest decodeClientRequest(int senderId, Message message) {
        byte[] payload = message.getPayload();

        if (isSystemNode(senderId)) {
            try {
                return ClientRequest.deserialize(payload);
            } catch (Exception ignored) {}
        }

        try {
            EncryptedPayload encrypted = EncryptedPayload.deserialize(payload);
            byte[] decryptedBytes = CryptoUtils.decryptHybrid(
                encrypted.getEncryptedKey(),
                encrypted.getIv(),
                encrypted.getEncryptedData(),
                keyManager.getPrivateKey()
            );
            return ClientRequest.deserialize(decryptedBytes);
        } catch (Exception ignored) {}

        try {
            return ClientRequest.deserialize(payload);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean verifyClientRequest(ClientRequest request) {
        byte[] signature = request.getSignature();
        byte[] publicKeyBytes = request.getClientPublicKey();
        if (
            signature == null ||
            signature.length == 0 ||
            publicKeyBytes.length == 0
        ) {
            return false;
        }

        byte[] knownKey = clientPublicKeys.get(request.getClientId());
        if (knownKey != null && !Arrays.equals(knownKey, publicKeyBytes)) {
            return false;
        }

        try {
            PublicKey publicKey = CryptoUtils.decodePublicKey(publicKeyBytes);
            boolean valid = CryptoUtils.verify(
                request.bytesToSign(),
                signature,
                publicKey
            );
            if (valid) {
                clientPublicKeys.putIfAbsent(
                    request.getClientId(),
                    publicKeyBytes
                );
            }
            return valid;
        } catch (Exception e) {
            return false;
        }
    }

    private void handlePrepare(int senderId, Message message) {
        PreparePayload payload;
        try {
            payload = PreparePayload.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (payload.getView() < currentView) {
                return;
            }
            if (payload.getView() > currentView) {
                moveToViewLocked(payload.getView(), "received PREPARE");
            }
            int expectedLeader = NetworkConfig.getLeader(currentView);
            if (senderId != expectedLeader) {
                return;
            }
            Block block = payload.getBlock();
            if (
                block.getView() != currentView ||
                block.getProposerId() != senderId ||
                !block.hasValidHash()
            ) {
                return;
            }
            QuorumCertificate justifyQc = payload.getJustifyQc();
            if (justifyQc != null) {
                if (
                    justifyQc.getView() > currentView ||
                    !justifyQc.verify(keyManager) ||
                    !justifyQc.getBlockHash().equals(block.getParentHash())
                ) {
                    return;
                }
            }
            if (!isSafeToVoteLocked(block, justifyQc)) {
                return;
            }

            if (justifyQc != null && isHigherQc(justifyQc, highQc)) {
                highQc = justifyQc;
            }
            blocksByHash.putIfAbsent(block.getHash(), block);
            activeBlockHash = block.getHash();
            activeStep = ConsensusStep.PREPARE;

            ConsensusVote vote = createVote(
                ConsensusPhase.PREPARE,
                currentView,
                block.getHash()
            );
            apl.send(
                expectedLeader,
                MessageType.PREPARE_VOTE,
                vote.serialize()
            );
            scheduleViewTimeoutLocked();
        }
    }

    private void handlePrepareVote(int senderId, Message message) {
        if (!isSystemNode(senderId)) {
            return;
        }
        ConsensusVote vote;
        try {
            vote = ConsensusVote.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (!isLeaderForCurrentViewLocked()) {
                return;
            }
            if (senderId != vote.getVoterId()) {
                return;
            }
            if (!vote.verify(keyManager)) {
                return;
            }
            if (
                vote.getPhase() != ConsensusPhase.PREPARE ||
                vote.getView() != currentView
            ) {
                return;
            }
            if (
                activeBlockHash == null ||
                !activeBlockHash.equals(vote.getBlockHash())
            ) {
                return;
            }
            onPrepareVoteLocked(vote);
        }
    }

    private void handlePreCommit(int senderId, Message message) {
        PhasePayload payload;
        try {
            payload = PhasePayload.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (payload.getView() < currentView) {
                return;
            }
            if (payload.getView() > currentView) {
                moveToViewLocked(payload.getView(), "received PRE_COMMIT");
            }
            int expectedLeader = NetworkConfig.getLeader(currentView);
            if (senderId != expectedLeader) {
                return;
            }
            QuorumCertificate qc = payload.getQuorumCertificate();
            if (
                qc.getPhase() != ConsensusPhase.PREPARE ||
                qc.getView() != payload.getView() ||
                !qc.getBlockHash().equals(payload.getBlockHash()) ||
                !qc.verify(keyManager)
            ) {
                return;
            }
            if (!ensureBlockPresentLocked(payload.getBlockHash(), payload.getBlock())) {
                return;
            }
            activeBlockHash = payload.getBlockHash();
            activeStep = ConsensusStep.PRE_COMMIT;
            highQc = qc;
            lockedQc = qc;

            ConsensusVote vote = createVote(
                ConsensusPhase.PRE_COMMIT,
                currentView,
                payload.getBlockHash()
            );
            apl.send(
                expectedLeader,
                MessageType.PRE_COMMIT_VOTE,
                vote.serialize()
            );
            scheduleViewTimeoutLocked();
        }
    }

    private void handlePreCommitVote(int senderId, Message message) {
        if (!isSystemNode(senderId)) {
            return;
        }
        ConsensusVote vote;
        try {
            vote = ConsensusVote.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (!isLeaderForCurrentViewLocked()) {
                return;
            }
            if (senderId != vote.getVoterId()) {
                return;
            }
            if (!vote.verify(keyManager)) {
                return;
            }
            if (
                vote.getPhase() != ConsensusPhase.PRE_COMMIT ||
                vote.getView() != currentView
            ) {
                return;
            }
            if (
                activeBlockHash == null ||
                !activeBlockHash.equals(vote.getBlockHash())
            ) {
                return;
            }
            onPreCommitVoteLocked(vote);
        }
    }

    private void handleCommit(int senderId, Message message) {
        PhasePayload payload;
        try {
            payload = PhasePayload.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (payload.getView() < currentView) {
                return;
            }
            if (payload.getView() > currentView) {
                moveToViewLocked(payload.getView(), "received COMMIT");
            }
            int expectedLeader = NetworkConfig.getLeader(currentView);
            if (senderId != expectedLeader) {
                return;
            }
            QuorumCertificate qc = payload.getQuorumCertificate();
            if (
                qc.getPhase() != ConsensusPhase.PRE_COMMIT ||
                qc.getView() != payload.getView() ||
                !qc.getBlockHash().equals(payload.getBlockHash()) ||
                !qc.verify(keyManager)
            ) {
                return;
            }
            if (!ensureBlockPresentLocked(payload.getBlockHash(), payload.getBlock())) {
                return;
            }
            activeBlockHash = payload.getBlockHash();
            activeStep = ConsensusStep.COMMIT;
            highQc = qc;

            ConsensusVote vote = createVote(
                ConsensusPhase.COMMIT,
                currentView,
                payload.getBlockHash()
            );
            apl.send(expectedLeader, MessageType.COMMIT_VOTE, vote.serialize());
            scheduleViewTimeoutLocked();
        }
    }

    private void handleCommitVote(int senderId, Message message) {
        if (!isSystemNode(senderId)) {
            return;
        }
        ConsensusVote vote;
        try {
            vote = ConsensusVote.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (!isLeaderForCurrentViewLocked()) {
                return;
            }
            if (senderId != vote.getVoterId()) {
                return;
            }
            if (!vote.verify(keyManager)) {
                return;
            }
            if (
                vote.getPhase() != ConsensusPhase.COMMIT ||
                vote.getView() != currentView
            ) {
                return;
            }
            if (
                activeBlockHash == null ||
                !activeBlockHash.equals(vote.getBlockHash())
            ) {
                return;
            }
            onCommitVoteLocked(vote);
        }
    }

    private void handleDecide(int senderId, Message message) {
        DecidePayload payload;
        try {
            payload = DecidePayload.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (payload.getView() < currentView) {
                return;
            }
            if (payload.getView() > currentView) {
                moveToViewLocked(payload.getView(), "received DECIDE");
            }
            int expectedLeader = NetworkConfig.getLeader(currentView);
            if (senderId != expectedLeader) {
                return;
            }
            QuorumCertificate qc = payload.getCommitQc();
            if (
                qc.getPhase() != ConsensusPhase.COMMIT ||
                qc.getView() != payload.getView() ||
                !qc.getBlockHash().equals(payload.getBlockHash()) ||
                !qc.verify(keyManager)
            ) {
                return;
            }
            if (!ensureBlockPresentLocked(payload.getBlockHash(), payload.getBlock())) {
                return;
            }
            commitBlockLocked(payload.getBlockHash(), qc);
        }
    }

    private void handleNewView(int senderId, Message message) {
        NewViewPayload payload;
        try {
            payload = NewViewPayload.deserialize(message.getPayload());
        } catch (Exception e) {
            return;
        }

        synchronized (lock) {
            if (payload.getNewView() < currentView) {
                return;
            }
            if (payload.getNewView() > currentView) {
                moveToViewLocked(payload.getNewView(), "received NEW_VIEW");
            }
            recordNewViewVoteLocked(payload.getNewView(), senderId);

            QuorumCertificate candidateQc = payload.getHighQc();
            if (candidateQc != null && candidateQc.verify(keyManager)) {
                if (
                    highQc == null || candidateQc.getView() > highQc.getView()
                ) {
                    highQc = candidateQc;
                }
            }

            sendNewViewLocked();

            if (
                isLeaderForCurrentViewLocked() &&
                hasNewViewQuorumLocked(currentView)
            ) {
                tryStartConsensusLocked();
            }
        }
    }

    private void onPrepareVoteLocked(ConsensusVote vote) {
        if (activeStep != ConsensusStep.PREPARE) {
            return;
        }
        addVoteLocked(prepareVotes, vote);
        if (
            !hasQuorumLocked(prepareVotes, vote.getView(), vote.getBlockHash())
        ) {
            return;
        }
        QuorumCertificate qc = buildQcLocked(
            prepareVotes,
            ConsensusPhase.PREPARE,
            vote.getView(),
            vote.getBlockHash()
        );
        if (!qc.verify(keyManager)) {
            return;
        }

        highQc = qc;
        activeStep = ConsensusStep.PRE_COMMIT;
        Block block = blocksByHash.get(vote.getBlockHash());
        PhasePayload payload = new PhasePayload(
            currentView,
            vote.getBlockHash(),
            block,
            qc
        );
        broadcastToOtherNodesLocked(
            MessageType.PRE_COMMIT,
            payload.serialize()
        );

        ConsensusVote selfVote = createVote(
            ConsensusPhase.PRE_COMMIT,
            currentView,
            vote.getBlockHash()
        );
        onPreCommitVoteLocked(selfVote);
    }

    private void onPreCommitVoteLocked(ConsensusVote vote) {
        if (activeStep != ConsensusStep.PRE_COMMIT) {
            return;
        }
        addVoteLocked(preCommitVotes, vote);
        if (
            !hasQuorumLocked(
                preCommitVotes,
                vote.getView(),
                vote.getBlockHash()
            )
        ) {
            return;
        }
        QuorumCertificate qc = buildQcLocked(
            preCommitVotes,
            ConsensusPhase.PRE_COMMIT,
            vote.getView(),
            vote.getBlockHash()
        );
        if (!qc.verify(keyManager)) {
            return;
        }

        highQc = qc;
        activeStep = ConsensusStep.COMMIT;
        Block block = blocksByHash.get(vote.getBlockHash());
        PhasePayload payload = new PhasePayload(
            currentView,
            vote.getBlockHash(),
            block,
            qc
        );
        broadcastToOtherNodesLocked(MessageType.COMMIT, payload.serialize());

        ConsensusVote selfVote = createVote(
            ConsensusPhase.COMMIT,
            currentView,
            vote.getBlockHash()
        );
        onCommitVoteLocked(selfVote);
    }

    private void onCommitVoteLocked(ConsensusVote vote) {
        if (activeStep != ConsensusStep.COMMIT) {
            return;
        }
        addVoteLocked(commitVotes, vote);
        if (
            !hasQuorumLocked(commitVotes, vote.getView(), vote.getBlockHash())
        ) {
            return;
        }
        QuorumCertificate qc = buildQcLocked(
            commitVotes,
            ConsensusPhase.COMMIT,
            vote.getView(),
            vote.getBlockHash()
        );
        if (!qc.verify(keyManager)) {
            return;
        }

        highQc = qc;
        Block block = blocksByHash.get(vote.getBlockHash());
        DecidePayload decidePayload = new DecidePayload(
            currentView,
            vote.getBlockHash(),
            block,
            qc
        );
        broadcastToOtherNodesLocked(
            MessageType.DECIDE,
            decidePayload.serialize()
        );
        commitBlockLocked(vote.getBlockHash(), qc);
    }

    private void commitBlockLocked(
        String blockHash,
        QuorumCertificate commitQc
    ) {
        if (committedBlockHashes.contains(blockHash)) {
            return;
        }
        Block block = blocksByHash.get(blockHash);
        if (block == null) {
            return;
        }
        if (decidedRequestIds.contains(block.getRequestId())) {
            committedBlockHashes.add(blockHash);
            return;
        }

        committedBlockHashes.add(blockHash);
        decidedRequestIds.add(block.getRequestId());
        pendingRequestIds.remove(block.getRequestId());
        pendingClientRequests.removeIf(r ->
            r.getRequestId().equals(block.getRequestId())
        );

        lastCommittedHash = blockHash;
        lastCommittedHeight = block.getHeight();
        highQc = commitQc;
        lockedQc = commitQc;

        appendToBlockchain(block.getData());

        if (repliedRequestIds.add(block.getRequestId())) {
            sendClientReply(block, true, "commited on view " + currentView);
        }

        activeBlockHash = null;
        activeClientRequest = null;
        activeStep = ConsensusStep.IDLE;
        savePersistentStateLocked();

        moveToViewLocked(
            Math.max(currentView, block.getView()) + 1,
            "block decided"
        );
        sendNewViewLocked();

        if (
            isLeaderForCurrentViewLocked() &&
            hasNewViewQuorumLocked(currentView)
        ) {
            tryStartConsensusLocked();
        }
    }

    private void tryStartConsensusLocked() {
        if (!running) {
            return;
        }
        if (!isLeaderForCurrentViewLocked()) {
            return;
        }
        if (currentView > 0 && !hasNewViewQuorumLocked(currentView)) {
            return;
        }
        if (activeBlockHash != null) {
            return;
        }

        ClientRequest nextRequest = null;
        while (!pendingClientRequests.isEmpty()) {
            ClientRequest candidate = pendingClientRequests.poll();
            if (candidate == null) {
                break;
            }
            if (decidedRequestIds.contains(candidate.getRequestId())) {
                pendingRequestIds.remove(candidate.getRequestId());
                continue;
            }
            nextRequest = candidate;
            break;
        }
        if (nextRequest == null) {
            return;
        }
        activeClientRequest = nextRequest;

        String parentHash = selectProposalParentHashLocked();
        int parentHeight = resolveBlockHeightLocked(parentHash);

        Block block = Block.create(
            parentHash,
            parentHeight + 1,
            currentView,
            nodeId,
            nextRequest.getRequestId(),
            nextRequest.getClientId(),
            nextRequest.getReplyHost(),
            nextRequest.getReplyPort(),
            nextRequest.getData(),
            nextRequest.getTimestamp()
        );
        blocksByHash.put(block.getHash(), block);
        activeBlockHash = block.getHash();
        activeStep = ConsensusStep.PREPARE;

        if (byzantineBehavior == ByzantineBehavior.EQUIVOCATE_LEADER) {
            for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
                if (i == nodeId) {
                    continue;
                }
                Block forkBlock = Block.create(
                    parentHash,
                    parentHeight + 1,
                    currentView,
                    nodeId,
                    nextRequest.getRequestId(),
                    nextRequest.getClientId(),
                    nextRequest.getReplyHost(),
                    nextRequest.getReplyPort(),
                    nextRequest.getData() + "#fork-" + i,
                    nextRequest.getTimestamp()
                );
                blocksByHash.put(forkBlock.getHash(), forkBlock);
                PreparePayload forkPayload = new PreparePayload(
                    currentView,
                    forkBlock,
                    highQc
                );
                apl.send(i, MessageType.PREPARE, forkPayload.serialize());
            }
        } else {
            PreparePayload preparePayload = new PreparePayload(
                currentView,
                block,
                highQc
            );
            broadcastToOtherNodesLocked(
                MessageType.PREPARE,
                preparePayload.serialize()
            );
        }
        scheduleViewTimeoutLocked();

        ConsensusVote selfVote = createVote(
            ConsensusPhase.PREPARE,
            currentView,
            block.getHash()
        );
        onPrepareVoteLocked(selfVote);
    }

    private void broadcastToOtherNodesLocked(MessageType type, byte[] payload) {
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            if (i == nodeId) {
                continue;
            }
            apl.send(i, type, payload);
        }
    }

    private void addVoteLocked(
        Map<String, Map<Integer, ConsensusVote>> store,
        ConsensusVote vote
    ) {
        String key = voteKey(vote.getView(), vote.getBlockHash());
        Map<Integer, ConsensusVote> votes = store.computeIfAbsent(
            key,
            ignored -> new HashMap<>()
        );
        votes.put(vote.getVoterId(), vote);
    }

    private boolean hasQuorumLocked(
        Map<String, Map<Integer, ConsensusVote>> store,
        int view,
        String blockHash
    ) {
        Map<Integer, ConsensusVote> votes = store.get(voteKey(view, blockHash));
        return votes != null && NetworkConfig.isQuorum(votes.size());
    }

    private QuorumCertificate buildQcLocked(
        Map<String, Map<Integer, ConsensusVote>> store,
        ConsensusPhase phase,
        int view,
        String blockHash
    ) {
        Map<Integer, ConsensusVote> votes = store.getOrDefault(
            voteKey(view, blockHash),
            Collections.emptyMap()
        );
        return QuorumCertificate.fromVotes(
            phase,
            view,
            blockHash,
            nodeId,
            keyManager,
            votes.values()
        );
    }

    private static String voteKey(int view, String blockHash) {
        return view + ":" + blockHash;
    }

    private boolean isSafeToVoteLocked(
        Block block,
        QuorumCertificate justifyQc
    ) {
        if (!isValidProposalParentLocked(block, justifyQc)) {
            return false;
        }
        if (lockedQc == null) {
            return true;
        }
        if (justifyQc != null && justifyQc.getView() > lockedQc.getView()) {
            return true;
        }
        return isBlockExtensionOfLocked(block, lockedQc.getBlockHash());
    }

    private boolean isValidProposalParentLocked(
        Block block,
        QuorumCertificate justifyQc
    ) {
        if (justifyQc != null) {
            return block.getParentHash().equals(justifyQc.getBlockHash());
        }
        return GENESIS_HASH.equals(block.getParentHash()) ||
        lastCommittedHash.equals(block.getParentHash());
    }

    private boolean isBlockExtensionOfLocked(Block block, String ancestorHash) {
        String currentHash = block.getParentHash();
        Set<String> visited = new HashSet<>();
        while (currentHash != null && visited.add(currentHash)) {
            if (ancestorHash.equals(currentHash)) {
                return true;
            }
            if (GENESIS_HASH.equals(currentHash)) {
                return false;
            }
            Block parent = blocksByHash.get(currentHash);
            if (parent == null) {
                return false;
            }
            currentHash = parent.getParentHash();
        }
        return false;
    }

    private String selectProposalParentHashLocked() {
        if (highQc != null) {
            String candidate = highQc.getBlockHash();
            if (
                candidate.equals(lastCommittedHash) ||
                blocksByHash.containsKey(candidate)
            ) {
                return candidate;
            }
        }
        return lastCommittedHash;
    }

    private int resolveBlockHeightLocked(String blockHash) {
        if (GENESIS_HASH.equals(blockHash)) {
            return 0;
        }
        if (lastCommittedHash.equals(blockHash)) {
            return lastCommittedHeight;
        }
        Block block = blocksByHash.get(blockHash);
        return block == null ? lastCommittedHeight : block.getHeight();
    }

    private boolean ensureBlockPresentLocked(String blockHash, Block block) {
        Block knownBlock = blocksByHash.get(blockHash);
        if (knownBlock != null) {
            return true;
        }
        if (
            block == null ||
            !blockHash.equals(block.getHash()) ||
            !block.hasValidHash()
        ) {
            return false;
        }
        blocksByHash.put(blockHash, block);
        return true;
    }

    private boolean isHigherQc(
        QuorumCertificate candidate,
        QuorumCertificate current
    ) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.getView() > current.getView();
    }

    private void moveToViewLocked(int newView, String reason) {
        if (newView <= currentView) {
            return;
        }
        if (
            activeClientRequest != null &&
            !decidedRequestIds.contains(activeClientRequest.getRequestId())
        ) {
            if (pendingRequestIds.add(activeClientRequest.getRequestId())) {
                pendingClientRequests.offer(activeClientRequest);
            }
        }
        activeClientRequest = null;
        currentView = newView;
        activeBlockHash = null;
        activeStep = ConsensusStep.IDLE;
        scheduleViewTimeoutLocked();
        savePersistentStateLocked();
        System.out.println(
            "[Node " +
                nodeId +
                "] now on view " +
                currentView +
                " (" +
                reason +
                ")"
        );
    }

    private void scheduleViewTimeoutLocked() {
        if (!running) {
            return;
        }
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        timeoutTask = scheduler.schedule(
            this::onViewTimeout,
            VIEW_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void onViewTimeout() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            if (byzantineBehavior == ByzantineBehavior.SILENT) {
                return;
            }
            int nextView = currentView + 1;
            moveToViewLocked(nextView, "timeout");
            sendNewViewLocked();
            if (
                isLeaderForCurrentViewLocked() &&
                hasNewViewQuorumLocked(currentView)
            ) {
                tryStartConsensusLocked();
            }
        }
    }

    private void sendNewViewLocked() {
        if (!recordNewViewVoteLocked(currentView, nodeId)) {
            return;
        }
        NewViewPayload payload = new NewViewPayload(currentView, highQc);
        broadcastToOtherNodesLocked(MessageType.NEW_VIEW, payload.serialize());
    }

    private boolean recordNewViewVoteLocked(int view, int voterId) {
        Set<Integer> voters = newViewVotes.computeIfAbsent(view, ignored ->
            new HashSet<>()
        );
        return voters.add(voterId);
    }

    private boolean hasNewViewQuorumLocked(int view) {
        if (view == 0) {
            return true;
        }
        Set<Integer> voters = newViewVotes.get(view);
        return voters != null && NetworkConfig.isQuorum(voters.size());
    }

    private boolean isLeaderForCurrentViewLocked() {
        return NetworkConfig.getLeader(currentView) == nodeId;
    }

    private boolean isSystemNode(int senderId) {
        return senderId >= 0 && senderId < NetworkConfig.NUM_NODES;
    }

    private ConsensusVote createVote(
        ConsensusPhase phase,
        int view,
        String blockHash
    ) {
        ConsensusVote vote = ConsensusVote.create(
            phase,
            view,
            blockHash,
            nodeId,
            keyManager
        );
        if (byzantineBehavior != ByzantineBehavior.INVALID_VOTE_SIGNATURE) {
            return vote;
        }
        byte[] corruptedSignature = vote.getSignature();
        if (corruptedSignature.length > 0) {
            corruptedSignature[0] = (byte) (corruptedSignature[0] ^ 0x5A);
        }
        return new ConsensusVote(
            phase,
            view,
            blockHash,
            nodeId,
            corruptedSignature
        );
    }

    private void loadPersistentStateLocked() {
        apl.restoreCurrentMessageId(0);
        apl.restoreReplayWindows(Collections.emptyMap());
        highQc = null;
        lockedQc = null;
        if (!persistenceEnabled) {
            return;
        }
        Optional<NodePersistentState> loadedState = stateStore.load();
        if (loadedState.isEmpty()) {
            return;
        }
        NodePersistentState state = loadedState.get();
        currentView = Math.max(0, state.getView());
        lastCommittedHash =
            state.getLastCommittedHash() == null
                ? GENESIS_HASH
                : state.getLastCommittedHash();
        lastCommittedHeight = Math.max(0, state.getLastCommittedHeight());

        blockchain.clear();
        blockchain.addAll(state.getBlockchain());

        decidedRequestIds.clear();
        decidedRequestIds.addAll(state.getDecidedRequestIds());
        repliedRequestIds.clear();
        repliedRequestIds.addAll(state.getRepliedRequestIds());

        pendingClientRequests.clear();
        pendingRequestIds.clear();
        blocksByHash.clear();
        prepareVotes.clear();
        preCommitVotes.clear();
        commitVotes.clear();
        newViewVotes.clear();

        committedBlockHashes.clear();
        if (!GENESIS_HASH.equals(lastCommittedHash)) {
            committedBlockHashes.add(lastCommittedHash);
        }

        apl.restoreCurrentMessageId(state.getLastUsedNetworkMessageId());
        apl.restoreReplayWindows(state.getAplReplayWindows());

        activeBlockHash = null;
        activeClientRequest = null;
        activeStep = ConsensusStep.IDLE;
    }

    private void savePersistentStateLocked() {
        if (!persistenceEnabled) {
            return;
        }
        NodePersistentState state = new NodePersistentState(
            currentView,
            lastCommittedHash,
            lastCommittedHeight,
            blockchain,
            decidedRequestIds,
            repliedRequestIds,
            apl.getCurrentMessageId(),
            apl.snapshotReplayWindows()
        );
        stateStore.save(state);
    }

    private void sendClientReply(Block block, boolean success, String status) {
        ClientReply reply = new ClientReply(
            block.getRequestId(),
            success,
            status,
            nodeId,
            currentView
        );
        sendClientReply(
            block.getClientHost(),
            block.getClientPort(),
            block.getClientId(),
            reply
        );
    }

    private void sendClientReply(
        ClientRequest request,
        boolean success,
        String status
    ) {
        ClientReply reply = new ClientReply(
            request.getRequestId(),
            success,
            status,
            nodeId,
            currentView
        );
        sendClientReply(
            request.getReplyHost(),
            request.getReplyPort(),
            request.getClientId(),
            reply
        );
    }

    private void sendClientReply(
        String host,
        int port,
        int clientId,
        ClientReply reply
    ) {
        try (DatagramSocket socket = new DatagramSocket()) {
            Message message = new Message(
                nodeId,
                clientId,
                System.nanoTime(),
                MessageType.CLIENT_REPLY,
                reply.serialize()
            );
            message.setSignature(keyManager.sign(message.getBytesToSign()));
            byte[] bytes = message.serialize();
            DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                InetAddress.getByName(host),
                port
            );
            socket.send(packet);
        } catch (Exception e) {
            System.err.println(
                "[Node " +
                    nodeId +
                    "] couldnt send client reply: " +
                    e.getMessage()
            );
        }
    }

    public int getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return port;
    }

    public AuthenticatedPerfectLinks getAPL() {
        return apl;
    }

    public KeyManager getKeyManager() {
        return keyManager;
    }

    public void clearPersistentState() {
        synchronized (lock) {
            stateStore.clear();
        }
    }

    @Override
    public String toString() {
        return (
            "Node{id=" +
            nodeId +
            ", port=" +
            port +
            ", view=" +
            getCurrentView() +
            ", blockchain=" +
            blockchain.size() +
            " items}"
        );
    }
}
