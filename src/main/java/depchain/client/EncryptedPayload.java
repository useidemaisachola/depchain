package depchain.client;

import java.io.*;

/**
 * Represents an encrypted payload using hybrid encryption (RSA + AES).
 * 
 * Structure:
 * - encryptedKey: AES key encrypted with RSA (recipient's public key)
 * - iv: Initialization vector for AES-CBC
 * - encryptedData: Actual data encrypted with AES
 */

public class EncryptedPayload implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final byte[] encryptedKey;   // RSA-encrypted AES key
    private final byte[] iv;             // AES IV
    private final byte[] encryptedData;  // AES-encrypted data

    public EncryptedPayload(byte[] encryptedKey, byte[] iv, byte[] encryptedData) {
        this.encryptedKey = encryptedKey;
        this.iv = iv;
        this.encryptedData = encryptedData;
    }

    public byte[] getEncryptedKey() { return encryptedKey; }
    public byte[] getIv() { return iv; }
    public byte[] getEncryptedData() { return encryptedData; }

    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize EncryptedPayload", e);
        }
    }

    public static EncryptedPayload deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (EncryptedPayload) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize EncryptedPayload", e);
        }
    }
}
