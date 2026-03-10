package depchain.net;

public enum MessageType {

    DATA,
    ACK,

    CLIENT_REQUEST,
    CLIENT_REPLY,

    PREPARE,
    PREPARE_VOTE,
    PRE_COMMIT,
    PRE_COMMIT_VOTE,
    COMMIT,
    COMMIT_VOTE,
    DECIDE,
    NEW_VIEW
}
