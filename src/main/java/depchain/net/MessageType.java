package depchain.net;

public enum MessageType {
    // Link layer messages
    DATA,           // Regular data message
    ACK,            // Acknowledgment for Perfect Links
    
    // Client messages
    CLIENT_REQUEST, // Client sends append request
    CLIENT_REPLY,   // Node replies to client
    
    // HotStuff consensus messages (for later)
    PREPARE,
    PREPARE_VOTE,
    PRE_COMMIT,
    PRE_COMMIT_VOTE,
    COMMIT,
    COMMIT_VOTE,
    DECIDE,
    NEW_VIEW
}
