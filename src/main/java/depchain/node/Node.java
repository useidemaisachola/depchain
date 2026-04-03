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
import depchain.blockchain.BlockStore;
import depchain.blockchain.EvmResult;
import depchain.blockchain.EvmService;
import depchain.blockchain.GenesisBlock;
import depchain.blockchain.GenesisLoader;
import depchain.blockchain.PersistedBlock;
import depchain.blockchain.Transaction;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Node implements AuthenticatedPerfectLinks.Listener, AutoCloseable {

    private static final String GENESIS_HASH = "GENESIS";
    private static final long VIEW_TIMEOUT_MS = 5000;
    private static final long DEFAULT_BLOCK_GAS_LIMIT = NetworkConfig.BLOCK_GAS_LIMIT;

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

    /** EVM world state — initialized from genesis on first startup. */
    private EvmService evmService;
    /** Address of the ISTCoin ERC-20 contract deployed in the genesis block. */
    private Address istCoinAddress;
    /** Persists committed blocks (including world state) to disk. */
    private final BlockStore blockStore;
    /** Hash of the last block written to the BlockStore; null before genesis is persisted. */
    private String lastPersistedBlockHash = null;

    /** Max cumulative gasLimit allowed per consensus block. */
    private final long blockGasLimit;

    /** Pending pool grouped by sender address, ordered by nonce. */
    private final Map<Address, NavigableMap<Long, ClientRequest>> pendingBySender;
    /** Index to remove a pending request in O(1). */
    private final Map<String, Address> pendingSenderByRequestId;
    /** Index to remove a pending request in O(1). */
    private final Map<String, Long> pendingNonceByRequestId;
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
    private List<ClientRequest> activeClientRequests;
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
        this(nodeId, keyManager, byzantineBehavior, stateDirectory, persistenceEnabled, DEFAULT_BLOCK_GAS_LIMIT);
    }

    public Node(
        int nodeId,
        KeyManager keyManager,
        ByzantineBehavior byzantineBehavior,
        String stateDirectory,
        boolean persistenceEnabled,
        long blockGasLimit
    ) {
        this.nodeId = nodeId;
        this.port = NetworkConfig.getNodePort(nodeId);
        this.keyManager = keyManager;
        this.blockchain = new CopyOnWriteArrayList<>();
        this.lock = new Object();
        this.byzantineBehavior = byzantineBehavior;
        this.persistenceEnabled = persistenceEnabled;
        this.stateStore   = new NodeStateStore(nodeId, stateDirectory);
        this.blockStore   = new BlockStore(stateDirectory + "/blocks");
        this.evmService   = new EvmService();
        this.istCoinAddress = null;

        this.blockGasLimit = blockGasLimit;

        this.pendingBySender = new HashMap<>();
        this.pendingSenderByRequestId = new HashMap<>();
        this.pendingNonceByRequestId = new HashMap<>();
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
        this.activeClientRequests = null;
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

    /** Returns the node's EVM service (world state + execution engine). */
    public EvmService getEvmService() { return evmService; }

    /** Returns the address of the ISTCoin contract deployed in the genesis block, or null. */
    public Address getIstCoinAddress() { return istCoinAddress; }

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
                sendClientReply(request, true, "already-decided");
                return;
            }

            Transaction tx = tryDeserializeTransaction(request.getData());
            if (tx == null) {
                sendClientReply(request, false, "invalid-transaction");
                return;
            }

            if (!validateTransactionRequest(request, tx)) {
                sendClientReply(request, false, "invalid-transaction");
                return;
            }

            if (!addToPendingPoolLocked(request, tx)) {
                // Duplicate request id or conflicting nonce already pending.
                return;
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

    /** Adds a validated transaction request to the pending pool (under {@code lock}). */
    private boolean addToPendingPoolLocked(ClientRequest request, Transaction tx) {
        String requestId = request.getRequestId();
        if (!pendingRequestIds.add(requestId)) {
            return false;
        }
        Address sender = tx.getFrom();
        long nonce = tx.getNonce();

        NavigableMap<Long, ClientRequest> byNonce = pendingBySender.computeIfAbsent(sender, ignored -> new TreeMap<>());
        if (byNonce.containsKey(nonce)) {
            pendingRequestIds.remove(requestId);
            System.err.println("[Node " + nodeId + "] rejected tx: duplicate pending nonce from "
                    + sender + " (nonce=" + nonce + ")");
            return false;
        }
        byNonce.put(nonce, request);
        pendingSenderByRequestId.put(requestId, sender);
        pendingNonceByRequestId.put(requestId, nonce);
        return true;
    }

    /** Removes a request from the pending pool if present (under {@code lock}). */
    private void removeFromPendingPoolLocked(String requestId) {
        Address sender = pendingSenderByRequestId.remove(requestId);
        Long nonce = pendingNonceByRequestId.remove(requestId);
        pendingRequestIds.remove(requestId);
        if (sender == null || nonce == null) {
            return;
        }
        NavigableMap<Long, ClientRequest> byNonce = pendingBySender.get(sender);
        if (byNonce == null) {
            return;
        }
        byNonce.remove(nonce);
        if (byNonce.isEmpty()) {
            pendingBySender.remove(sender);
        }
    }

    /** Attempts to deserialize a Base64-encoded {@link Transaction}; returns null if not a transaction. */
    private static Transaction tryDeserializeTransaction(String base64Data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Data);
            return Transaction.deserialize(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates that adding {@code newTx} would not cause the sender's DepCoin balance
     * to go negative when considering all of their pending transactions combined.
     * Must be called under {@code lock}.
     */
    private String validatePendingPoolFundsLocked(Transaction newTx) {
        Address sender = newTx.getFrom();
        Wei balance = evmService.getBalance(sender);

        BigInteger reserved = BigInteger.ZERO;
        // Include the in-flight request currently being decided.
        if (activeClientRequests != null) {
            for (ClientRequest r : activeClientRequests) {
                if (r == null || decidedRequestIds.contains(r.getRequestId())) {
                    continue;
                }
                Transaction activeTx = tryDeserializeTransaction(r.getData());
                if (activeTx != null && sender.equals(activeTx.getFrom())) {
                    reserved = reserved.add(maxDepCoinCost(activeTx));
                }
            }
        }
        // Include all transactions still in the pending pool.
        NavigableMap<Long, ClientRequest> byNonce = pendingBySender.get(sender);
        if (byNonce != null) {
            for (ClientRequest r : byNonce.values()) {
                Transaction pendingTx = tryDeserializeTransaction(r.getData());
                if (pendingTx != null) {
                    reserved = reserved.add(maxDepCoinCost(pendingTx));
                }
            }
        }

        BigInteger newCost = maxDepCoinCost(newTx);
        BigInteger total = reserved.add(newCost);
        if (balance.getAsBigInteger().compareTo(total) < 0) {
            return "insufficient balance for pending txs: have " + balance.getAsBigInteger()
                    + ", pending=" + reserved + ", new=" + newCost + ", total=" + total;
        }
        return null;
    }

    /** Maximum DepCoin cost reserved by a transaction: value + gasPrice * gasLimit. */
    private static BigInteger maxDepCoinCost(Transaction tx) {
        BigInteger gas = BigInteger.valueOf(tx.getGasPrice()).multiply(BigInteger.valueOf(tx.getGasLimit()));
        return gas.add(tx.getValue().getAsBigInteger());
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
            if (!valid) {
                return false;
            }
            clientPublicKeys.putIfAbsent(request.getClientId(), publicKeyBytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pre-pool validation: checks that the request carries a well-formed signed
     * {@link Transaction} that can plausibly execute before it is added to the
     * pending queue.
     */
    private boolean validateTransactionRequest(ClientRequest request, Transaction tx) {
        String basicError = tx.validateBasicFields();
        if (basicError != null) {
            System.err.println("[Node " + nodeId + "] rejected tx: " + basicError);
            return false;
        }

        Address sender = tx.getFrom();
        Wei maxFee = Wei.of(tx.getGasPrice() * tx.getGasLimit());
        Wei required = maxFee.add(tx.getValue());

        PublicKey pubKey;
        try {
            pubKey = CryptoUtils.decodePublicKey(request.getClientPublicKey());
        } catch (Exception e) {
            System.err.println("[Node " + nodeId + "] rejected tx: cannot decode client key");
            return false;
        }

        // Sender authorization: tx.from must match the address derived from the key
        // that signed this ClientRequest.
        Address derivedFrom;
        try {
            derivedFrom = EvmService.deriveAddress(pubKey);
        } catch (Exception e) {
            System.err.println("[Node " + nodeId + "] rejected tx: cannot derive EVM address from client key");
            return false;
        }
        if (!sender.equals(derivedFrom)) {
            System.err.println("[Node " + nodeId + "] rejected tx: from mismatch, claimed="
                    + sender + " derived=" + derivedFrom);
            return false;
        }

        // Transaction signature verification: tx must be signed by the same key.
        if (!tx.verifySignature(pubKey)) {
            System.err.println("[Node " + nodeId + "] rejected tx: invalid transaction signature");
            return false;
        }

        // Balance pre-check: sender must cover worst-case fee + value.
        if (evmService.getBalance(sender).compareTo(required) < 0) {
            System.err.println("[Node " + nodeId + "] rejected tx: insufficient balance from " + sender);
            return false;
        }

        // Nonce pre-check: reject obviously wrong nonces early.
        long expectedNonce = evmService.getNonce(sender);
        if (tx.getNonce() < expectedNonce) {
            System.err.println("[Node " + nodeId + "] rejected tx: stale nonce from " + sender
                    + " (expected>=" + expectedNonce + ", got=" + tx.getNonce() + ")");
            return false;
        }

        String fundsError = validatePendingPoolFundsLocked(tx);
        if (fundsError != null) {
            System.err.println("[Node " + nodeId + "] rejected tx: " + fundsError);
            return false;
        }

        return true;
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
        committedBlockHashes.add(blockHash);

        lastCommittedHash = blockHash;
        lastCommittedHeight = block.getHeight();
        highQc = commitQc;
        lockedQc = commitQc;

        List<ExecutedTx> executed = new ArrayList<>();

        for (ClientRequest request : block.getRequests()) {
            if (request == null) {
                continue;
            }
            String requestId = request.getRequestId();

            // Mark decided and drop from pending pool if present.
            decidedRequestIds.add(requestId);
            removeFromPendingPoolLocked(requestId);

            Transaction tx = tryDeserializeTransaction(request.getData());
            if (tx == null) {
                if (repliedRequestIds.add(requestId)) {
                    sendClientReply(request, false, "invalid-transaction-in-block");
                }
                continue;
            }

            EvmResult evmResult;
            try {
                evmResult = evmService.executeTransaction(tx);
            } catch (Exception e) {
                System.err.println("[Node " + nodeId + "] could not execute tx in block "
                        + block.getHeight() + ": " + e.getMessage());
                evmResult = null;
            }
            executed.add(new ExecutedTx(tx, evmResult));

            appendToBlockchain(request.getData());

            if (repliedRequestIds.add(requestId)) {
                boolean success = evmResult != null && evmResult.success();
                String replyMsg;
                if (evmResult == null) {
                    replyMsg = "execution-error";
                } else {
                    replyMsg = "gasUsed=" + evmResult.gasUsed()
                            + ",fee=" + evmResult.fee().getAsBigInteger()
                            + ",success=" + evmResult.success();

                    if (evmResult.output() != null && !evmResult.output().isEmpty()) {
                        replyMsg += ",output=" + evmResult.output().toHexString();
                    }
                }
                sendClientReply(request, success, replyMsg);
            }
        }

        // Persist the block with the resulting world state.
        if (persistenceEnabled) {
            persistBlockLocked(block, executed);
        }

        activeBlockHash = null;
        activeClientRequests = null;
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

    /**
     * Extracts the fee (gasPrice × gasLimit) from a client request whose
     * {@code data} field is a Base64-encoded serialized {@link Transaction}.
     * Returns {@code 0} if the payload is not a transaction.
     */
    public static long extractFee(ClientRequest req) {
        try {
            byte[] bytes = Base64.getDecoder().decode(req.getData());
            Transaction tx = Transaction.deserialize(bytes);
            return tx.getGasPrice() * tx.getGasLimit();
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<ClientRequest> selectRequestsForNextBlockLocked() {
        // Nonce is the primary constraint per-sender: a sender's nonce sequence
        // must be continuous (no gaps). Under a block gas cap, we also want the
        // fee-based choice to consider that picking a low-fee head (e.g., A1)
        // may unlock a high-fee next nonce (e.g., A2). We approximate this by
        // scoring each sender by the total fee of the longest consecutive nonce
        // prefix that fits in the *remaining* gas budget.

        Map<Address, Long> baseNonce = new HashMap<>();
        Map<Address, Long> selectedCount = new HashMap<>();
        for (Address sender : pendingBySender.keySet()) {
            baseNonce.put(sender, evmService.getNonce(sender));
            selectedCount.put(sender, 0L);
        }

        long gasSoFar = 0L;
        List<ClientRequest> selected = new ArrayList<>();

        while (gasSoFar < blockGasLimit) {
            long remainingGas = blockGasLimit - gasSoFar;

            Address bestSender = null;
            long bestBundleFee = -1L;
            long bestHeadFee = -1L;
            long bestHeadNonce = -1L;
            ClientRequest bestHeadReq = null;
            long bestHeadGas = 0L;

            for (Address sender : new ArrayList<>(pendingBySender.keySet())) {
                NavigableMap<Long, ClientRequest> byNonce = pendingBySender.get(sender);
                if (byNonce == null || byNonce.isEmpty()) {
                    continue;
                }

                long startNonce = baseNonce.getOrDefault(sender, evmService.getNonce(sender))
                        + selectedCount.getOrDefault(sender, 0L);
                ClientRequest headReq = byNonce.get(startNonce);
                if (headReq == null) {
                    continue;
                }
                Transaction headTx = tryDeserializeTransaction(headReq.getData());
                if (headTx == null) {
                    continue;
                }

                long headGas = headTx.getGasLimit();
                if (headGas > remainingGas) {
                    continue;
                }

                // Compute best consecutive prefix fee starting at startNonce.
                long bundleFee = 0L;
                long bundleGas = 0L;
                long nonce = startNonce;
                while (true) {
                    ClientRequest req = byNonce.get(nonce);
                    if (req == null) {
                        break;
                    }
                    Transaction tx = tryDeserializeTransaction(req.getData());
                    if (tx == null) {
                        break;
                    }
                    long gas = tx.getGasLimit();
                    if (bundleGas + gas > remainingGas) {
                        break;
                    }
                    bundleGas += gas;
                    bundleFee += tx.getGasPrice() * tx.getGasLimit();
                    nonce++;
                }

                long headFee = headTx.getGasPrice() * headTx.getGasLimit();

                boolean better = false;
                if (bundleFee > bestBundleFee) {
                    better = true;
                } else if (bundleFee == bestBundleFee) {
                    // Tie-break deterministically.
                    if (headFee > bestHeadFee) {
                        better = true;
                    } else if (headFee == bestHeadFee) {
                        String senderHex = sender.toHexString();
                        String bestHex = bestSender != null ? bestSender.toHexString() : "";
                        if (senderHex.compareTo(bestHex) < 0) {
                            better = true;
                        } else if (senderHex.equals(bestHex) && startNonce < bestHeadNonce) {
                            better = true;
                        }
                    }
                }

                if (better) {
                    bestSender = sender;
                    bestBundleFee = bundleFee;
                    bestHeadFee = headFee;
                    bestHeadNonce = startNonce;
                    bestHeadReq = headReq;
                    bestHeadGas = headGas;
                }
            }

            if (bestSender == null || bestHeadReq == null) {
                break;
            }

            // Add the chosen head request to the block.
            removeFromPendingPoolLocked(bestHeadReq.getRequestId());
            selected.add(bestHeadReq);
            gasSoFar += bestHeadGas;

            long count = selectedCount.getOrDefault(bestSender, 0L) + 1L;
            selectedCount.put(bestSender, count);
        }

        return selected;
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

        List<ClientRequest> batch = selectRequestsForNextBlockLocked();
        if (batch.isEmpty()) {
            return;
        }

        activeClientRequests = batch;

        String parentHash = selectProposalParentHashLocked();
        int parentHeight = resolveBlockHeightLocked(parentHash);

        Block block = Block.create(
            parentHash,
            parentHeight + 1,
            currentView,
            nodeId,
            batch,
            System.currentTimeMillis()
        );
        blocksByHash.put(block.getHash(), block);
        activeBlockHash = block.getHash();
        activeStep = ConsensusStep.PREPARE;

        PreparePayload preparePayload = new PreparePayload(
            currentView,
            block,
            highQc
        );
        broadcastToOtherNodesLocked(
            MessageType.PREPARE,
            preparePayload.serialize()
        );
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
        if (activeClientRequests != null) {
            for (ClientRequest r : activeClientRequests) {
                if (r == null || decidedRequestIds.contains(r.getRequestId())) {
                    continue;
                }
                Transaction tx = tryDeserializeTransaction(r.getData());
                if (tx == null) {
                    continue;
                }
                addToPendingPoolLocked(r, tx);
            }
        }
        activeClientRequests = null;
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

    /**
     * Initializes the EVM world state from the genesis block on the very first
     * startup (no persisted state found on disk).
     *
     * <p>Uses the node public keys to derive deterministic EVM addresses, funds
     * each genesis account, and deploys the ISTCoin ERC-20 contract.
     * Failures are logged but do not prevent the node from starting (the EVM
     * state simply stays empty and will be populated once real blocks arrive).
     */
    private void initializeFromGenesisLocked() {
        try {
            Map<Integer, java.security.PublicKey> nodeKeys = keyManager.getAllPublicKeys();
            GenesisLoader.Result result = GenesisLoader.load(
                    nodeKeys,
                    keyManager.getExtraParticipantPublicKeys());
            this.evmService     = result.evmService();
            this.istCoinAddress = result.istCoinAddress();
            System.out.println("[Node " + nodeId + "] genesis loaded — ISTCoin at "
                    + istCoinAddress);

            if (persistenceEnabled) {
                persistGenesisBlockLocked(result.block());
            }
        } catch (Exception e) {
            System.err.println("[Node " + nodeId
                    + "] WARNING: genesis loading failed: " + e.getMessage()
                    + " — starting with empty EVM state");
        }
    }

    /** Persists the genesis state (height 0, null previous hash) to the block store. */
    private void persistGenesisBlockLocked(GenesisBlock genesisBlock) {
        try {
            List<PersistedBlock.TransactionEntry> txEntries = new ArrayList<>();
            for (GenesisBlock.TransactionEntry gtx : genesisBlock.getTransactions()) {
                txEntries.add(new PersistedBlock.TransactionEntry(
                        gtx.from, gtx.to,
                        gtx.input != null ? gtx.input : "",
                        gtx.gasPrice, gtx.gasLimit,
                        0L, "0", true
                ));
            }

            Map<String, PersistedBlock.AccountEntry> worldState = buildWorldStateSnapshot();

            PersistedBlock genesis = new PersistedBlock(null, 0, txEntries, worldState);
            blockStore.save(genesis);
            lastPersistedBlockHash = genesis.getBlockHash();
            System.out.println("[Node " + nodeId + "] genesis block persisted: "
                    + lastPersistedBlockHash);
        } catch (Exception e) {
            System.err.println("[Node " + nodeId
                    + "] WARNING: failed to persist genesis block: " + e.getMessage());
        }
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
            initializeFromGenesisLocked();
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

        pendingBySender.clear();
        pendingSenderByRequestId.clear();
        pendingNonceByRequestId.clear();
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
        activeClientRequests = null;
        activeStep = ConsensusStep.IDLE;

        if (persistenceEnabled) {
            restoreEvmFromChainLocked();
        }
    }

    private static final class ExecutedTx {
        final Transaction tx;
        final EvmResult result;

        ExecutedTx(Transaction tx, EvmResult result) {
            this.tx = tx;
            this.result = result;
        }
    }

    private void persistBlockLocked(Block block, List<ExecutedTx> executedTxs) {
        try {
            List<PersistedBlock.TransactionEntry> txEntries = new ArrayList<>();
            if (executedTxs != null) {
                for (ExecutedTx e : executedTxs) {
                    if (e == null || e.tx == null || e.result == null) {
                        continue;
                    }
                    Transaction tx = e.tx;
                    EvmResult evmResult = e.result;
                    txEntries.add(new PersistedBlock.TransactionEntry(
                            tx.getFrom().toHexString(),
                            tx.getTo().map(Address::toHexString).orElse(null),
                            tx.getData().isEmpty() ? "" : tx.getData().toHexString(),
                            tx.getGasPrice(),
                            tx.getGasLimit(),
                            evmResult.gasUsed(),
                            evmResult.fee().getAsBigInteger().toString(),
                            evmResult.success()
                    ));
                }
            }

            Map<String, PersistedBlock.AccountEntry> worldState = buildWorldStateSnapshot();

            PersistedBlock persisted = new PersistedBlock(
                    lastPersistedBlockHash,
                    block.getHeight(),
                    txEntries,
                    worldState
            );
            blockStore.save(persisted);
            lastPersistedBlockHash = persisted.getBlockHash();
        } catch (Exception e) {
            System.err.println("[Node " + nodeId + "] failed to persist block "
                    + block.getHeight() + ": " + e.getMessage());
        }
    }

    private Map<String, PersistedBlock.AccountEntry> buildWorldStateSnapshot() {
        Map<String, PersistedBlock.AccountEntry> worldState = new LinkedHashMap<>();
        for (Map.Entry<Address, depchain.blockchain.Account> entry
                : evmService.snapshotWorldState().entrySet()) {
            depchain.blockchain.Account acc = entry.getValue();
            Map<String, String> storage = new LinkedHashMap<>();
            acc.storage().forEach((k, v) ->
                    storage.put(k.toHexString(), v.toHexString()));
            worldState.put(entry.getKey().toHexString(), new PersistedBlock.AccountEntry(
                    acc.balance().getAsBigInteger().toString(),
                    acc.nonce(),
                    acc.code().isEmpty() ? "" : acc.code().toHexString(),
                    storage
            ));
        }
        return worldState;
    }

    /**
     * Restores the EVM world state from the persisted block chain on startup.
     * Falls back to genesis if the chain is absent or fails integrity validation.
     */
    private void restoreEvmFromChainLocked() {
        List<PersistedBlock> chain = blockStore.loadChain();
        if (chain.isEmpty()) {
            initializeFromGenesisLocked();
            return;
        }
        if (!blockStore.validateChain(chain)) {
            System.err.println("[Node " + nodeId
                    + "] WARNING: block chain integrity check failed — re-initializing from genesis");
            initializeFromGenesisLocked();
            return;
        }

        PersistedBlock lastBlock = chain.get(chain.size() - 1);
        this.evmService = new EvmService();
        evmService.restoreWorldState(lastBlock.getWorldState());

        // Re-derive ISTCoin address from node0's public key (deterministic).
        try {
            Map<Integer, java.security.PublicKey> nodeKeys = keyManager.getAllPublicKeys();
            Address deployer = EvmService.deriveAddress(nodeKeys.get(0));
            this.istCoinAddress = Address.contractAddress(deployer, 0);
        } catch (Exception e) {
            System.err.println("[Node " + nodeId
                    + "] WARNING: could not restore istCoinAddress: " + e.getMessage());
        }

        lastPersistedBlockHash = lastBlock.getBlockHash();
        System.out.println("[Node " + nodeId + "] EVM state restored from chain ("
                + chain.size() + " blocks, last height=" + lastBlock.getHeight() + ")");
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
