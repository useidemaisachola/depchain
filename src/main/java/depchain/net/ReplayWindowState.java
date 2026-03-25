package depchain.net;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

public class ReplayWindowState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long highestSeen;
    private final Set<Long> seenMessageIds;

    public ReplayWindowState(long highestSeen, Set<Long> seenMessageIds) {
        this.highestSeen = highestSeen;
        this.seenMessageIds = seenMessageIds == null
            ? new HashSet<>()
            : new HashSet<>(seenMessageIds);
    }

    public long getHighestSeen() {
        return highestSeen;
    }

    public Set<Long> getSeenMessageIds() {
        return new HashSet<>(seenMessageIds);
    }
}
