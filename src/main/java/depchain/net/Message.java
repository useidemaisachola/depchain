package depchain.net;

import java.util.UUID;

public class Message {

    private UUID senderId;
    private UUID receiverId;
    private UUID messageId;
    private MessageType messageType;
    private String payload;

    public Message(
        String payload,
        UUID senderId,
        UUID receiverId,
        UUID messageId,
        MessageType messageType
    ) {
        this.payload = payload;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.messageId = messageId;
        this.messageType = messageType;
    }

    public Message(String payload) {
        this.payload = payload;
    }

    public String getPayload() {
        return payload;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getReceiverId() {
        return receiverId;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    @Override
    public String toString() {
        return (
            "Message{" +
            "senderId=" +
            senderId +
            ", receiverId=" +
            receiverId +
            ", messageId=" +
            messageId +
            ", messageType=" +
            messageType +
            ", payload='" +
            payload +
            '\'' +
            '}'
        );
    }
}
