package depchain.crypto;

import java.nio.file.*;
import java.security.*;
import java.util.*;

public class KeyManager {

    private final int nodeId;
    private final PrivateKey privateKey;
    private final Map<Integer, PublicKey> publicKeys;

    public KeyManager(
        int nodeId,
        PrivateKey privateKey,
        Map<Integer, PublicKey> publicKeys
    ) {
        this.nodeId = nodeId;
        this.privateKey = privateKey;
        this.publicKeys = new HashMap<>(publicKeys);
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey(int nodeId) {
        return publicKeys.get(nodeId);
    }

    public boolean hasPublicKey(int nodeId) {
        return publicKeys.containsKey(nodeId);
    }

    public Map<Integer, PublicKey> getAllPublicKeys() {
        return Collections.unmodifiableMap(publicKeys);
    }

    public byte[] sign(byte[] data) {
        return CryptoUtils.sign(data, privateKey);
    }

    public boolean verify(int senderId, byte[] data, byte[] signature) {
        PublicKey pk = publicKeys.get(senderId);
        if (pk == null) {
            System.err.println("dont know node: " + senderId);
            return false;
        }
        return CryptoUtils.verify(data, signature, pk);
    }

    public static void generateAllKeys(int numNodes, String keysDirectory)
        throws Exception {
        Path keysDir = Paths.get(keysDirectory);
        Files.createDirectories(keysDir);

        for (int i = 0; i < numNodes; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            String basePath = keysDir.resolve("node" + i).toString();
            CryptoUtils.saveKeyPair(kp, basePath);
            System.out.println("made keys for node " + i);
        }
    }

    public static KeyManager loadForNode(
        int nodeId,
        int numNodes,
        String keysDirectory
    ) throws Exception {
        Path keysDir = Paths.get(keysDirectory);

        String privateKeyPath = keysDir
            .resolve("node" + nodeId + ".key")
            .toString();
        PrivateKey privateKey = CryptoUtils.loadPrivateKey(privateKeyPath);

        Map<Integer, PublicKey> publicKeys = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            String publicKeyPath = keysDir
                .resolve("node" + i + ".pub")
                .toString();
            PublicKey pk = CryptoUtils.loadPublicKey(publicKeyPath);
            publicKeys.put(i, pk);
        }

        return new KeyManager(nodeId, privateKey, publicKeys);
    }

    public static Map<Integer, KeyManager> generateInMemory(int numNodes) {
        Map<Integer, KeyPair> keyPairs = new HashMap<>();
        Map<Integer, PublicKey> publicKeys = new HashMap<>();

        for (int i = 0; i < numNodes; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            keyPairs.put(i, kp);
            publicKeys.put(i, kp.getPublic());
        }

        Map<Integer, KeyManager> managers = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            managers.put(
                i,
                new KeyManager(i, keyPairs.get(i).getPrivate(), publicKeys)
            );
        }

        return managers;
    }

    public int getNodeId() {
        return nodeId;
    }
}
