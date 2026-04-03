package depchain.client;

import depchain.blockchain.EvmService;
import depchain.blockchain.Transaction;
import depchain.config.NetworkConfig;
import depchain.crypto.CryptoUtils;
import depchain.net.*;
import depchain.net.FairLossLinks.NodeAddress;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.hyperledger.besu.datatypes.Address;

public class BlockchainClient {

    private final int clientId;
    private final KeyPair keyPair;
    private final Map<Integer, PublicKey> nodePublicKeys;
    private final DatagramSocket socket;
    private final int localPort;
    private final BlockingQueue<Message> replyQueue;
    private Thread receiveThread;
    private volatile boolean running;

    private static final int TIMEOUT_MS = 12000;

    public BlockchainClient(int clientId, int localPort) throws Exception {
        this(clientId, localPort, CryptoUtils.generateKeyPair());
    }

    public BlockchainClient(int clientId, int localPort, KeyPair keyPair) throws Exception {
        this.clientId = clientId;
        this.localPort = localPort;
        this.keyPair = keyPair;
        this.nodePublicKeys = new HashMap<>();
        this.replyQueue = new LinkedBlockingQueue<>();
        this.socket = new DatagramSocket(localPort);
        this.running = false;
    }

    public void loadNodeKeys(String keysDirectory) throws Exception {
        for (int i = 0; i < NetworkConfig.NUM_NODES; i++) {
            String publicKeyPath = keysDirectory + "/node" + i + ".pub";
            PublicKey pk = CryptoUtils.loadPublicKey(publicKeyPath);
            nodePublicKeys.put(i, pk);
        }
        System.out.println(
            "[Client " +
                clientId +
                "] loaded pub keys for " +
                nodePublicKeys.size() +
                " nodes"
        );
    }

    public void setNodePublicKeys(Map<Integer, PublicKey> publicKeys) {
        nodePublicKeys.clear();
        nodePublicKeys.putAll(publicKeys);
    }

    public void start() {
        running = true;
        receiveThread = new Thread(
            this::receiveLoop,
            "client-" + clientId + "-recv"
        );
        receiveThread.setDaemon(true);
        receiveThread.start();
        System.out.println("[Client " + clientId + "] up on port " + localPort);
    }

    public void stop() {
        running = false;
        socket.close();
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        System.out.println("[Client " + clientId + "] stopped");
    }

    public boolean submitRequest(String data) throws Exception {
        ClientRequest request = buildSignedRequest(data);
        for (int nodeId = 0; nodeId < NetworkConfig.NUM_NODES; nodeId++) {
            sendRequestToNode(nodeId, request);
        }
        return waitForReply(request.getRequestId());
    }

    public boolean submitRequestToNode(int nodeId, String data)
        throws Exception {
        ClientRequest request = buildSignedRequest(data);
        sendRequestToNode(nodeId, request);
        return waitForReply(request.getRequestId());
    }

    /**
     * Signs {@code tx} with this client's RSA private key, encodes it as
     * Base64, and submits it to all nodes as a {@link ClientRequest}.
     *
     * <p>Returns {@code true} when {@code f+1} nodes report EVM execution
     * success; returns {@code false} immediately when {@code f+1} nodes report
     * execution failure (fee deducted, nonce incremented, but operation failed).
     *
     * @param tx an <em>unsigned</em> or already-signed transaction
     * @return {@code true} if the transaction was committed and the EVM
     *         execution succeeded on a quorum of nodes
     */
    public boolean submitTransaction(Transaction tx) throws Exception {
        Transaction signed = tx.isSigned() ? tx : tx.sign(keyPair.getPrivate());
        String data = Base64.getEncoder().encodeToString(signed.serialize());
        return submitRequest(data);
    }

    /**
     * Returns the EVM address derived from this client's RSA public key.
     *
     * <p>Use this address as the {@code from} field when building transactions.
     */
    public Address getEvmAddress() {
        return EvmService.deriveAddress(keyPair.getPublic());
    }

    private ClientRequest buildSignedRequest(String data) {
        String requestId = UUID.randomUUID().toString();
        ClientRequest unsignedRequest = new ClientRequest(
            clientId,
            requestId,
            data,
            System.currentTimeMillis(),
            "localhost",
            localPort,
            keyPair.getPublic().getEncoded(),
            null
        );
        byte[] signature = CryptoUtils.sign(
            unsignedRequest.bytesToSign(),
            keyPair.getPrivate()
        );
        return unsignedRequest.withSignature(signature);
    }

    private void sendRequestToNode(int nodeId, ClientRequest request)
        throws Exception {
        PublicKey nodePublicKey = nodePublicKeys.get(nodeId);
        if (nodePublicKey == null) {
            throw new IllegalStateException(
                "No publicc key for node " + nodeId
            );
        }

        byte[] requestBytes = request.serialize();
        EncryptedPayload encrypted = encryptHybrid(requestBytes, nodePublicKey);

        Message message = new Message(
            clientId,
            nodeId,
            System.nanoTime(),
            MessageType.CLIENT_REQUEST,
            encrypted.serialize()
        );

        sendToNode(nodeId, message);
        System.out.println(
            "[Client " +
                clientId +
                "] sent req " +
                request.getRequestId() +
                " to node " +
                nodeId
        );
    }

    private boolean waitForReply(String requestId) {
        // Require f+1 matching replies for Byzantine safety.
        // Count success and failure replies separately so that a transaction
        // that fails EVM execution returns false quickly instead of timing out.
        int requiredReplies = NetworkConfig.MAX_FAULTS + 1;
        Set<Integer> successResponders = new HashSet<>();
        Set<Integer> failureResponders = new HashSet<>();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            try {
                Message replyMessage = replyQueue.poll(
                    remaining,
                    TimeUnit.MILLISECONDS
                );
                if (replyMessage == null) {
                    break;
                }
                if (!isValidSignedNodeMessage(replyMessage)) {
                    continue;
                }
                if (replyMessage.getType() != MessageType.CLIENT_REPLY) {
                    continue;
                }
                ClientReply reply = ClientReply.deserialize(
                    replyMessage.getPayload()
                );
                if (!requestId.equals(reply.getRequestId())) {
                    continue;
                }
                if (replyMessage.getSenderId() != reply.getResponderNodeId()) {
                    continue;
                }
                System.out.println(
                    "[Client " +
                        clientId +
                        "] got reply frm node " +
                        reply.getResponderNodeId() +
                        " (view " +
                        reply.getView() +
                        "): " +
                        reply.getMessage()
                );
                if (reply.isSuccess()) {
                    if (successResponders.add(reply.getResponderNodeId())
                            && successResponders.size() >= requiredReplies) {
                        return true;
                    }
                } else {
                    if (failureResponders.add(reply.getResponderNodeId())
                            && failureResponders.size() >= requiredReplies) {
                        // Quorum agrees the tx failed (OOG / revert / bad input).
                        System.out.println("[Client " + clientId + "] req "
                                + requestId + " failed: " + reply.getMessage());
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ignored) {
            }
        }
        System.out.println(
            "[Client " + clientId + "] req " + requestId + " timed out " +
            "(success=" + successResponders.size() +
            ", failure=" + failureResponders.size() +
            ", need " + requiredReplies + ")"
        );
        return false;
    }

    private EncryptedPayload encryptHybrid(byte[] data, PublicKey nodePublicKey)
        throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
        byte[] encryptedData = aesCipher.doFinal(data);

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, nodePublicKey);
        byte[] encryptedKey = rsaCipher.doFinal(aesKey.getEncoded());

        return new EncryptedPayload(encryptedKey, iv, encryptedData);
    }

    private void sendToNode(int nodeId, Message message) throws Exception {
        NodeAddress addr = NetworkConfig.getAllNodeAddresses().get(nodeId);
        byte[] data = message.serialize();
        DatagramPacket packet = new DatagramPacket(
            data,
            data.length,
            InetAddress.getByName(addr.host),
            addr.port
        );
        socket.send(packet);
    }

    private void receiveLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(
                    packet.getData(),
                    0,
                    data,
                    0,
                    packet.getLength()
                );

                Message message = Message.deserialize(data);
                if (
                    message.getType() == MessageType.CLIENT_REPLY &&
                    isValidSignedNodeMessage(message)
                ) {
                    replyQueue.offer(message);
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println(
                        "[Client " +
                            clientId +
                            "] recv got weird: " +
                            e.getMessage()
                    );
                }
            }
        }
    }

    private boolean isValidSignedNodeMessage(Message message) {
        int senderId = message.getSenderId();
        PublicKey publicKey = nodePublicKeys.get(senderId);
        if (publicKey == null) {
            return false;
        }
        byte[] signature = message.getSignature();
        if (signature == null || signature.length == 0) {
            return false;
        }
        return CryptoUtils.verify(
            message.getBytesToSign(),
            signature,
            publicKey
        );
    }

    public int getClientId() {
        return clientId;
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }
}
