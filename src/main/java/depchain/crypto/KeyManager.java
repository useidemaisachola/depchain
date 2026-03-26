package depchain.crypto;

import depchain.config.NetworkConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import threshsig.GroupKey;
import threshsig.KeyShare;
import threshsig.ThresholdSignatures;

public class KeyManager {
    private static final int THRESHOLD_KEY_SIZE_BITS = 512;
    private static final String THRESHOLD_GROUP_KEY_FILE = "threshold.group";
    private static final String THRESHOLD_SHARE_SUFFIX = ".thshare";
    private static final Object THRESHOLD_LOCK = new Object();

    private static ThresholdMaterial cachedThresholdMaterial;

    private final int nodeId;
    private final PrivateKey privateKey;
    private final Map<Integer, PublicKey> publicKeys;
    private final GroupKey thresholdGroupKey;
    private final KeyShare thresholdKeyShare;
    private final Object thresholdShareLock;
    // Pairwise HMAC keys derived from RSA private keys.
    // outgoingMacKeys[j] = key used to MAC messages I send to node j.
    // incomingMacKeys[j] = key used to verify MACs on messages I receive from node j.
    private final Map<Integer, byte[]> outgoingMacKeys;
    private final Map<Integer, byte[]> incomingMacKeys;

    public KeyManager(
        int nodeId,
        PrivateKey privateKey,
        Map<Integer, PublicKey> publicKeys,
        GroupKey thresholdGroupKey,
        KeyShare thresholdKeyShare,
        Map<Integer, byte[]> outgoingMacKeys,
        Map<Integer, byte[]> incomingMacKeys
    ) {
        this.nodeId = nodeId;
        this.privateKey = privateKey;
        this.publicKeys = new HashMap<>(publicKeys);
        this.thresholdGroupKey = thresholdGroupKey;
        this.thresholdKeyShare = thresholdKeyShare;
        this.thresholdShareLock = new Object();
        this.outgoingMacKeys = new HashMap<>(outgoingMacKeys);
        this.incomingMacKeys = new HashMap<>(incomingMacKeys);
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

    public byte[] computeMac(int destNodeId, byte[] data) {
        byte[] key = outgoingMacKeys.get(destNodeId);
        if (key == null) {
            throw new IllegalStateException("No outgoing MAC key for node " + destNodeId);
        }
        return CryptoUtils.hmac(key, data);
    }

    public boolean verifyMac(int senderId, byte[] data, byte[] mac) {
        byte[] key = incomingMacKeys.get(senderId);
        if (key == null || mac == null || mac.length == 0) {
            return false;
        }
        byte[] expected = CryptoUtils.hmac(key, data);
        return MessageDigest.isEqual(expected, mac);
    }

    public byte[] signThresholdShare(byte[] data) {
        synchronized (thresholdShareLock) {
            return ThresholdSignatures.signShare(thresholdKeyShare, data);
        }
    }

    public boolean verifyThresholdShare(
        int senderId,
        byte[] data,
        byte[] signatureShare
    ) {
        return ThresholdSignatures.verifyShare(
            thresholdGroupKey,
            senderId + 1,
            data,
            signatureShare
        );
    }

    public byte[] combineThresholdShares(
        byte[] data,
        Collection<byte[]> signatureShares
    ) {
        return ThresholdSignatures.combine(
            thresholdGroupKey,
            data,
            signatureShares
        );
    }

    public boolean verifyThresholdSignature(byte[] data, byte[] signature) {
        return ThresholdSignatures.verifyCombined(
            thresholdGroupKey,
            data,
            signature
        );
    }

    public byte[] normalizeThresholdSignature(byte[] signature) {
        return ThresholdSignatures.normalizeSignature(
            thresholdGroupKey,
            signature
        );
    }

    public static void generateAllKeys(int numNodes, String keysDirectory)
        throws Exception {
        Path keysDir = Paths.get(keysDirectory);
        Files.createDirectories(keysDir);

        Map<Integer, KeyPair> keyPairs = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            keyPairs.put(i, kp);
            String basePath = keysDir.resolve("node" + i).toString();
            CryptoUtils.saveKeyPair(kp, basePath);
            System.out.println("made keys for node " + i);
        }

        // Derive and save pairwise HMAC keys: hmac_i_j.key is the key node i
        // uses to MAC messages sent to node j (and node j uses to verify them).
        for (int i = 0; i < numNodes; i++) {
            for (int j = 0; j < numNodes; j++) {
                if (i != j) {
                    byte[] macKey = CryptoUtils.deriveHmacKey(
                        keyPairs.get(i).getPrivate(), j
                    );
                    Files.write(keysDir.resolve("hmac_" + i + "_" + j + ".key"), macKey);
                }
            }
        }
        System.out.println("derived pairwise HMAC keys");

        writeThresholdMaterial(keysDir, buildThresholdMaterial(numNodes));
        System.out.println("made threshold QC key material");
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

        ensureThresholdMaterial(keysDir, numNodes);
        GroupKey groupKey = ThresholdSignatures.deserializeGroupKey(
            Files.readAllBytes(keysDir.resolve(THRESHOLD_GROUP_KEY_FILE))
        );
        KeyShare thresholdShare = ThresholdSignatures.deserializeKeyShare(
            Files.readAllBytes(keysDir.resolve(thresholdShareFileName(nodeId))),
            groupKey
        );

        // Outgoing MAC keys: derived from own private key (deterministic).
        // Incoming MAC keys: read from pre-generated files (derived from other nodes' private keys).
        Map<Integer, byte[]> outgoingMacKeys = new HashMap<>();
        Map<Integer, byte[]> incomingMacKeys = new HashMap<>();
        for (int j = 0; j < numNodes; j++) {
            if (j != nodeId) {
                outgoingMacKeys.put(j, CryptoUtils.deriveHmacKey(privateKey, j));
                incomingMacKeys.put(j, Files.readAllBytes(
                    keysDir.resolve("hmac_" + j + "_" + nodeId + ".key")
                ));
            }
        }

        return new KeyManager(
            nodeId,
            privateKey,
            publicKeys,
            groupKey,
            thresholdShare,
            outgoingMacKeys,
            incomingMacKeys
        );
    }

    public static Map<Integer, KeyManager> generateInMemory(int numNodes) {
        Map<Integer, KeyPair> keyPairs = new HashMap<>();
        Map<Integer, PublicKey> publicKeys = new HashMap<>();

        for (int i = 0; i < numNodes; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            keyPairs.put(i, kp);
            publicKeys.put(i, kp.getPublic());
        }

        ThresholdMaterial material = getCachedThresholdMaterial(numNodes);
        Map<Integer, KeyManager> managers = new HashMap<>();
        for (int i = 0; i < numNodes; i++) {
            KeyShare thresholdShare = ThresholdSignatures.deserializeKeyShare(
                material.serializedShares.get(i),
                material.groupKey
            );
            Map<Integer, byte[]> outgoingMacKeys = new HashMap<>();
            Map<Integer, byte[]> incomingMacKeys = new HashMap<>();
            for (int j = 0; j < numNodes; j++) {
                if (j != i) {
                    outgoingMacKeys.put(j, CryptoUtils.deriveHmacKey(keyPairs.get(i).getPrivate(), j));
                    incomingMacKeys.put(j, CryptoUtils.deriveHmacKey(keyPairs.get(j).getPrivate(), i));
                }
            }
            managers.put(
                i,
                new KeyManager(
                    i,
                    keyPairs.get(i).getPrivate(),
                    publicKeys,
                    material.groupKey,
                    thresholdShare,
                    outgoingMacKeys,
                    incomingMacKeys
                )
            );
        }

        return managers;
    }

    public int getNodeId() {
        return nodeId;
    }

    private static ThresholdMaterial getCachedThresholdMaterial(int numNodes) {
        synchronized (THRESHOLD_LOCK) {
            if (
                cachedThresholdMaterial == null ||
                cachedThresholdMaterial.serializedShares.size() != numNodes
            ) {
                cachedThresholdMaterial = buildThresholdMaterial(numNodes);
            }
            return cachedThresholdMaterial;
        }
    }

    private static void ensureThresholdMaterial(Path keysDir, int numNodes)
        throws IOException {
        synchronized (THRESHOLD_LOCK) {
            if (hasThresholdMaterial(keysDir, numNodes)) {
                return;
            }
            writeThresholdMaterial(keysDir, buildThresholdMaterial(numNodes));
        }
    }

    private static boolean hasThresholdMaterial(Path keysDir, int numNodes) {
        if (!Files.exists(keysDir.resolve(THRESHOLD_GROUP_KEY_FILE))) {
            return false;
        }
        for (int i = 0; i < numNodes; i++) {
            if (!Files.exists(keysDir.resolve(thresholdShareFileName(i)))) {
                return false;
            }
        }
        return true;
    }

    private static ThresholdMaterial buildThresholdMaterial(int numNodes) {
        ThresholdSignatures.GeneratedKeys generatedKeys =
            ThresholdSignatures.generateKeys(
                NetworkConfig.QUORUM_SIZE,
                numNodes,
                THRESHOLD_KEY_SIZE_BITS
            );
        GroupKey groupKey = generatedKeys.getGroupKey();
        byte[] serializedGroupKey = ThresholdSignatures.serializeGroupKey(
            groupKey
        );

        Map<Integer, byte[]> serializedShares = new HashMap<>();
        KeyShare[] shares = generatedKeys.getShares();
        for (int i = 0; i < shares.length; i++) {
            serializedShares.put(
                i,
                ThresholdSignatures.serializeKeyShare(shares[i])
            );
        }

        return new ThresholdMaterial(
            groupKey,
            serializedGroupKey,
            serializedShares
        );
    }

    private static void writeThresholdMaterial(
        Path keysDir,
        ThresholdMaterial material
    ) throws IOException {
        Files.write(
            keysDir.resolve(THRESHOLD_GROUP_KEY_FILE),
            material.serializedGroupKey
        );
        for (Map.Entry<Integer, byte[]> entry : material.serializedShares.entrySet()) {
            Files.write(
                keysDir.resolve(thresholdShareFileName(entry.getKey())),
                entry.getValue()
            );
        }
    }

    private static String thresholdShareFileName(int nodeId) {
        return "node" + nodeId + THRESHOLD_SHARE_SUFFIX;
    }

    private static final class ThresholdMaterial {
        private final GroupKey groupKey;
        private final byte[] serializedGroupKey;
        private final Map<Integer, byte[]> serializedShares;

        private ThresholdMaterial(
            GroupKey groupKey,
            byte[] serializedGroupKey,
            Map<Integer, byte[]> serializedShares
        ) {
            this.groupKey = groupKey;
            this.serializedGroupKey = serializedGroupKey;
            this.serializedShares = serializedShares;
        }
    }
}
