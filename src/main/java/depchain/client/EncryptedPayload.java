package depchain.client;

import java.io.*;

public class EncryptedPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final byte[] encryptedKey;
    private final byte[] iv;
    private final byte[] encryptedData;

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
