package depchain.blockchain;

import java.io.Serializable;
import java.util.TreeMap;

/**
 * An immutable point-in-time capture of a {@link WorldState}.
 *
 * Obtain via {@link WorldState#snapshot()} and restore via
 * {@link WorldState#rollback(WorldStateSnapshot)}.
 */
public final class WorldStateSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final TreeMap<String, WorldState.Entry> accounts;

    WorldStateSnapshot(TreeMap<String, WorldState.Entry> accounts) {
        this.accounts = accounts;
    }

    TreeMap<String, WorldState.Entry> accountsCopy() {
        TreeMap<String, WorldState.Entry> copy = new TreeMap<>();
        for (var me : accounts.entrySet()) {
            WorldState.Entry src = me.getValue();
            WorldState.Entry dst = new WorldState.Entry();
            dst.type    = src.type;
            dst.balance = src.balance;
            dst.nonce   = src.nonce;
            dst.code    = src.code;
            dst.storage = new TreeMap<>(src.storage);
            copy.put(me.getKey(), dst);
        }
        return copy;
    }
}
