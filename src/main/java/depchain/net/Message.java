package depchain.net;

import java.io.*;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private int senderId;           // Node ID (0-3)
    private int receiverId;         // Node ID (0-3)
    private long messageId;         // Unique message identifier
    private MessageType type;
    private byte[] payload;
    private byte[] signature;       // Digital signature (for APL)

    public Message(int senderId, int receiverId, long messageId, MessageType type, byte[] payload) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.messageId = messageId;
        this.type = type;
        this.payload = payload;
        this.signature = null;
    }

    // Convenience constructor for string payloads
    public Message(int senderId, int receiverId, long messageId, MessageType type, String payload) {
        this(senderId, receiverId, messageId, type, payload.getBytes());
    }

    // Getters
    public int getSenderId() { return senderId; }
    public int getReceiverId() { return receiverId; }
    public long getMessageId() { return messageId; }
    public MessageType getType() { return type; }
    public byte[] getPayload() { return payload; }
    public byte[] getSignature() { return signature; }
    public String getPayloadAsString() { return new String(payload); }

    // Setters
    public void setSignature(byte[] signature) { this.signature = signature; }
    public void setType(MessageType type) { this.type = type; }

    // Serialization
    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    public static Message deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Message) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize message", e);
        }
    }

    // Get bytes to sign (excludes signature itself)
    public byte[] getBytesToSign() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(senderId);
            dos.writeInt(receiverId);
            dos.writeLong(messageId);
            dos.writeUTF(type.name());
            dos.writeInt(payload.length);
            dos.write(payload);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get bytes to sign", e);
        }
    }

    @Override
    public String toString() {
        return String.format("Message{from=%d, to=%d, id=%d, type=%s, payload=%s}",
            senderId, receiverId, messageId, type, 
            payload.length <= 50 ? getPayloadAsString() : "[" + payload.length + " bytes]");
    }
}
