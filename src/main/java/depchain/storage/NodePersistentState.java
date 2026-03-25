package depchain.storage;

import depchain.net.ReplayWindowState;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NodePersistentState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int view;
    private final String lastCommittedHash;
    private final int lastCommittedHeight;
    private final List<String> blockchain;
    private final Set<String> decidedRequestIds;
    private final Set<String> repliedRequestIds;
    private final long lastUsedNetworkMessageId;
    private final Map<Integer, ReplayWindowState> aplReplayWindows;

    public NodePersistentState(int view,
                               String lastCommittedHash,
                               int lastCommittedHeight,
                               List<String> blockchain,
                               Set<String> decidedRequestIds,
                               Set<String> repliedRequestIds,
                               long lastUsedNetworkMessageId,
                               Map<Integer, ReplayWindowState> aplReplayWindows) {
        this.view = view;
        this.lastCommittedHash = lastCommittedHash;
        this.lastCommittedHeight = lastCommittedHeight;
        this.blockchain = new ArrayList<>(blockchain);
        this.decidedRequestIds = new HashSet<>(decidedRequestIds);
        this.repliedRequestIds = new HashSet<>(repliedRequestIds);
        this.lastUsedNetworkMessageId = lastUsedNetworkMessageId;
        this.aplReplayWindows = aplReplayWindows == null
            ? new HashMap<>()
            : new HashMap<>(aplReplayWindows);
    }

    public int getView() {
        return view;
    }

    public String getLastCommittedHash() {
        return lastCommittedHash;
    }

    public int getLastCommittedHeight() {
        return lastCommittedHeight;
    }

    public List<String> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public Set<String> getDecidedRequestIds() {
        return new HashSet<>(decidedRequestIds);
    }

    public Set<String> getRepliedRequestIds() {
        return new HashSet<>(repliedRequestIds);
    }

    public long getLastUsedNetworkMessageId() {
        return lastUsedNetworkMessageId;
    }

    public Map<Integer, ReplayWindowState> getAplReplayWindows() {
        return aplReplayWindows == null
            ? new HashMap<>()
            : new HashMap<>(aplReplayWindows);
    }
}
