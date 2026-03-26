package threshsig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ThresholdSignatures {
    private ThresholdSignatures() {}

    public static GeneratedKeys generateKeys(int threshold, int participants, int keySizeBits) {
        Dealer dealer = new Dealer(keySizeBits);
        dealer.generateKeys(threshold, participants);
        return new GeneratedKeys(dealer.getGroupKey(), dealer.getShares());
    }

    public static byte[] serializeGroupKey(GroupKey groupKey) {
        try (
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos)
        ) {
            dos.writeInt(groupKey.getK());
            dos.writeInt(groupKey.getL());
            writeBigInteger(dos, groupKey.getExponent());
            writeBigInteger(dos, groupKey.getModulus());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to serialize threshold group key");
        }
    }

    public static GroupKey deserializeGroupKey(byte[] data) {
        try (
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bis)
        ) {
            int k = dis.readInt();
            int l = dis.readInt();
            BigInteger exponent = readBigInteger(dis);
            BigInteger modulus = readBigInteger(dis);
            return new GroupKey(k, l, 0, BigInteger.ZERO, exponent, modulus);
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to deserialize threshold group key");
        }
    }

    public static byte[] serializeKeyShare(KeyShare keyShare) {
        try (
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos)
        ) {
            dos.writeInt(keyShare.getId());
            writeBigInteger(dos, keyShare.getSecret());
            writeBigInteger(dos, keyShare.getVerifier());
            writeBigInteger(dos, keyShare.getGroupVerifier());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to serialize threshold key share");
        }
    }

    public static KeyShare deserializeKeyShare(byte[] data, GroupKey groupKey) {
        try (
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bis)
        ) {
            int id = dis.readInt();
            BigInteger secret = readBigInteger(dis);
            BigInteger verifier = readBigInteger(dis);
            BigInteger groupVerifier = readBigInteger(dis);

            KeyShare share = new KeyShare(
                id,
                secret,
                groupKey.getModulus(),
                factorial(groupKey.getL())
            );
            share.setVerifiers(verifier, groupVerifier);
            return share;
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to deserialize threshold key share");
        }
    }

    public static byte[] signShare(KeyShare keyShare, byte[] data) {
        return serializeSigShare(keyShare.sign(hash(data)));
    }

    public static boolean verifyShare(
        GroupKey groupKey,
        int expectedShareId,
        byte[] data,
        byte[] serializedShare
    ) {
        try {
            SigShare share = deserializeSigShare(serializedShare);
            if (share.getId() != expectedShareId) {
                return false;
            }
            return verifyShareProof(groupKey, hash(data), share);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static byte[] combine(
        GroupKey groupKey,
        byte[] data,
        Collection<byte[]> serializedShares
    ) {
        Map<Integer, SigShare> orderedShares = new TreeMap<>();
        for (byte[] serializedShare : serializedShares) {
            SigShare share = deserializeSigShare(serializedShare);
            orderedShares.putIfAbsent(share.getId(), share);
        }

        if (orderedShares.size() < groupKey.getK()) {
            throw new ThresholdSigException("Not enough threshold shares to form a QC");
        }

        List<SigShare> selectedShares = new ArrayList<>(orderedShares.values());
        selectedShares.sort(Comparator.comparingInt(SigShare::getId));
        selectedShares = selectedShares.subList(0, groupKey.getK());

        byte[] hashedData = hash(data);
        for (SigShare share : selectedShares) {
            if (!verifyShareProof(groupKey, hashedData, share)) {
                throw new ThresholdSigException(
                    "Invalid threshold share from signer " + share.getId()
                );
            }
        }

        BigInteger modulus = groupKey.getModulus();
        BigInteger delta = factorial(groupKey.getL());
        SigShare[] shareArray = selectedShares.toArray(new SigShare[0]);

        BigInteger combined = BigInteger.ONE;
        for (SigShare share : shareArray) {
            combined =
                combined
                    .multiply(
                        share
                            .getSig()
                            .modPow(lagrange(share.getId(), shareArray, delta), modulus)
                    )
                    .mod(modulus);
        }

        byte[] serializedSignature = normalizeSignature(groupKey, combined.toByteArray());
        if (!verifyCombined(groupKey, data, serializedSignature)) {
            throw new ThresholdSigException("Combined threshold signature failed verification");
        }
        return serializedSignature;
    }

    public static boolean verifyCombined(GroupKey groupKey, byte[] data, byte[] signature) {
        try {
            BigInteger modulus = groupKey.getModulus();
            BigInteger representative = messageRepresentative(groupKey, data);
            BigInteger delta = factorial(groupKey.getL());
            BigInteger exponentPrime = delta.multiply(delta).shiftLeft(2);
            BigInteger combined = new BigInteger(signature).mod(modulus);
            return combined.modPow(groupKey.getExponent(), modulus).equals(
                representative.modPow(exponentPrime, modulus)
            );
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static byte[] normalizeSignature(GroupKey groupKey, byte[] signature) {
        return new BigInteger(signature).mod(groupKey.getModulus()).toByteArray();
    }

    static BigInteger factorial(int value) {
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= value; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }

    private static BigInteger lagrange(int shareId, SigShare[] shares, BigInteger delta) {
        BigInteger value = delta;
        for (SigShare share : shares) {
            if (share.getId() != shareId) {
                value = value.multiply(BigInteger.valueOf(share.getId()));
            }
        }
        for (SigShare share : shares) {
            if (share.getId() != shareId) {
                value = value.divide(BigInteger.valueOf(share.getId() - shareId));
            }
        }
        return value;
    }

    private static boolean verifyShareProof(GroupKey groupKey, byte[] hashedData, SigShare share) {
        BigInteger modulus = groupKey.getModulus();
        BigInteger delta = factorial(groupKey.getL());
        BigInteger representative = new BigInteger(hashedData).mod(modulus);
        BigInteger xTilde =
            representative.modPow(ThreshUtil.FOUR.multiply(delta), modulus);

        Verifier verifier = share.getSigVerifier();
        MessageDigest digest = sha1();
        digest.update(verifier.getGroupVerifier().toByteArray());
        digest.update(xTilde.toByteArray());
        digest.update(verifier.getShareVerifier().toByteArray());
        digest.update(
            share.getSig().modPow(ThreshUtil.TWO, modulus).toByteArray()
        );

        BigInteger vTerm = verifier.getGroupVerifier().modPow(verifier.getZ(), modulus);
        BigInteger vInverse =
            verifier
                .getShareVerifier()
                .modPow(verifier.getC(), modulus)
                .modInverse(modulus);
        digest.update(vTerm.multiply(vInverse).mod(modulus).toByteArray());

        BigInteger xTildeTerm = xTilde.modPow(verifier.getZ(), modulus);
        BigInteger sigInverse =
            share
                .getSig()
                .modPow(verifier.getC(), modulus)
                .modInverse(modulus);
        digest.update(xTildeTerm.multiply(sigInverse).mod(modulus).toByteArray());

        BigInteger challenge = new BigInteger(digest.digest()).mod(modulus);
        return challenge.equals(verifier.getC());
    }

    private static BigInteger messageRepresentative(GroupKey groupKey, byte[] data) {
        return new BigInteger(hash(data)).mod(groupKey.getModulus());
    }

    private static byte[] serializeSigShare(SigShare sigShare) {
        try (
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos)
        ) {
            dos.writeInt(sigShare.getId());
            writeBigInteger(dos, sigShare.getSig());
            writeBigInteger(dos, sigShare.getSigVerifier().getZ());
            writeBigInteger(dos, sigShare.getSigVerifier().getC());
            writeBigInteger(dos, sigShare.getSigVerifier().getShareVerifier());
            writeBigInteger(dos, sigShare.getSigVerifier().getGroupVerifier());
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to serialize threshold share");
        }
    }

    private static SigShare deserializeSigShare(byte[] data) {
        try (
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bis)
        ) {
            int id = dis.readInt();
            BigInteger signature = readBigInteger(dis);
            BigInteger z = readBigInteger(dis);
            BigInteger c = readBigInteger(dis);
            BigInteger shareVerifier = readBigInteger(dis);
            BigInteger groupVerifier = readBigInteger(dis);
            return new SigShare(
                id,
                signature,
                new Verifier(z, c, shareVerifier, groupVerifier)
            );
        } catch (IOException e) {
            throw new ThresholdSigException("Failed to deserialize threshold share");
        }
    }

    private static byte[] hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new ThresholdSigException("SHA-256 digest is not available");
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA");
        } catch (NoSuchAlgorithmException e) {
            throw new ThresholdSigException("SHA digest is not available");
        }
    }

    private static void writeBigInteger(DataOutputStream dos, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray();
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static BigInteger readBigInteger(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        byte[] bytes = dis.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of threshold signature payload");
        }
        return new BigInteger(bytes);
    }

    public static final class GeneratedKeys {
        private final GroupKey groupKey;
        private final KeyShare[] shares;

        GeneratedKeys(GroupKey groupKey, KeyShare[] shares) {
            this.groupKey = groupKey;
            this.shares = shares;
        }

        public GroupKey getGroupKey() {
            return groupKey;
        }

        public KeyShare[] getShares() {
            return shares.clone();
        }
    }
}
