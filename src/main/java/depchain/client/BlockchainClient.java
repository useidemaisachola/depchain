package depchain.client;

import depchain.config.NetworkConfig;
import depchain.crypto.CryptoUtils;
import depchain.net.*;
import depchain.net.FairLossLinks.NodeAddress;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/*Client library for interacting with the DepChain blockchain.
 Sends encrypted requests to blockchain nodes and waits for confirmation.*/

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
        this.clientId = clientId;
        this.localPort = localPort;
        this.keyPair = CryptoUtils.generateKeyPair();
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
                "] Loaded public keys for " +
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
        System.out.println(
            "[Client " + clientId + "] Started on port " + localPort
        );
    }

    public void stop() {
        running = false;
        socket.close();
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        System.out.println("[Client " + clientId + "] Stopped");
    }

    /* Submit request to the leader node */
    public boolean submitRequest(String data) throws Exception {
        ClientRequest request = buildSignedRequest(data);
        for (int nodeId = 0; nodeId < NetworkConfig.NUM_NODES; nodeId++) {
            sendRequestToNode(nodeId, request);
        }
        return waitForReply(request.getRequestId());
    }

    /* Submit request to a specific node */
    public boolean submitRequestToNode(int nodeId, String data)
        throws Exception {
        ClientRequest request = buildSignedRequest(data);
        sendRequestToNode(nodeId, request);
        return waitForReply(request.getRequestId());
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
            throw new IllegalStateException("No public key for node " + nodeId);
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
                "] Sent request " +
                request.getRequestId() +
                " to Node " +
                nodeId
        );
    }

    private boolean waitForReply(String requestId) {
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
                if (replyMessage.getType() != MessageType.CLIENT_REPLY) {
                    continue;
                }
                ClientReply reply = ClientReply.deserialize(
                    replyMessage.getPayload()
                );
                if (!requestId.equals(reply.getRequestId())) {
                    continue;
                }
                System.out.println(
                    "[Client " +
                        clientId +
                        "] Reply from node " +
                        reply.getResponderNodeId() +
                        " (view " +
                        reply.getView() +
                        "): " +
                        reply.getMessage()
                );
                return reply.isSuccess();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ignored) {
                // Ignore malformed or unexpected replies.
            }
        }
        System.out.println(
            "[Client " + clientId + "] Request " + requestId + " timed out"
        );
        return false;
    }

    /* Hybrid encryption: RSA encrypts AES key, AES encrypts data */
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
                if (message.getType() == MessageType.CLIENT_REPLY) {
                    replyQueue.offer(message);
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println(
                        "[Client " +
                            clientId +
                            "] Receive error: " +
                            e.getMessage()
                    );
                }
            }
        }
    }

    public int getClientId() {
        return clientId;
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }
}
