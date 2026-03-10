package depchain.crypto;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;

public class CryptoUtils {

    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
            keyGen.initialize(KEY_SIZE, new SecureRandom());
            return keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA algorithm not available", e);
        }
    }

    public static byte[] sign(byte[] data, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign data", e);
        }
    }

    public static boolean verify(
        byte[] data,
        byte[] signatureBytes,
        PublicKey publicKey
    ) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            System.err.println("sig check blew up: " + e.getMessage());
            return false;
        }
    }

    public static void saveKeyPair(KeyPair keyPair, String basePath)
        throws IOException {
        Path privateKeyPath = Paths.get(basePath + ".key");
        Files.write(privateKeyPath, keyPair.getPrivate().getEncoded());

        Path publicKeyPath = Paths.get(basePath + ".pub");
        Files.write(publicKeyPath, keyPair.getPublic().getEncoded());
    }

    public static PrivateKey loadPrivateKey(String path) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(path));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePrivate(spec);
    }

    public static PublicKey loadPublicKey(String path) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(path));
        return decodePublicKey(keyBytes);
    }

    public static PublicKey decodePublicKey(byte[] keyBytes) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
        return kf.generatePublic(spec);
    }

    public static byte[] hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] decryptHybrid(
        byte[] encryptedKey,
        byte[] iv,
        byte[] encryptedData,
        PrivateKey privateKey
    ) throws Exception {
        javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance(
            "RSA/ECB/PKCS1Padding"
        );
        rsaCipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedKey);

        javax.crypto.spec.SecretKeySpec aesKey =
            new javax.crypto.spec.SecretKeySpec(aesKeyBytes, "AES");

        javax.crypto.Cipher aesCipher = javax.crypto.Cipher.getInstance(
            "AES/CBC/PKCS5Padding"
        );
        aesCipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            aesKey,
            new javax.crypto.spec.IvParameterSpec(iv)
        );
        return aesCipher.doFinal(encryptedData);
    }
}
