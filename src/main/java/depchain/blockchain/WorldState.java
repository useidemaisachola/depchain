package depchain.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/**
 * Global account state for DepChain.
 *
 * <p>Holds all accounts (EOA and Contract) as an in-memory store. The backing
 * map is a {@link TreeMap} keyed by lowercase hex address, which guarantees
 * deterministic iteration order — a requirement for identical state hashes
 * across all nodes.
 *
 * <p>Typical usage within a block:
 * <pre>{@code
 *   WorldStateSnapshot snap = worldState.snapshot();
 *   try {
 *       // apply transaction...
 *   } catch (Exception e) {
 *       worldState.rollback(snap);   // revert on failure
 *   }
 * }</pre>
 */
public class WorldState implements Serializable {

    private static final long serialVersionUID = 1L;

    private TreeMap<String, Entry> accounts = new TreeMap<>();


    static final class Entry implements Serializable {
        private static final long serialVersionUID = 1L;

        String type;       // AccountType.name()
        String balance;    // decimal string (Wei as BigInteger)
        long   nonce;
        String code;       // "0x" hex string; empty string for EOA
        TreeMap<String, String> storage; // UInt256 hex -> UInt256 hex (sorted)

        Entry() { storage = new TreeMap<>(); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry e)) return false;
            return nonce == e.nonce
                    && Objects.equals(type, e.type)
                    && Objects.equals(balance, e.balance)
                    && Objects.equals(code, e.code)
                    && Objects.equals(storage, e.storage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, balance, nonce, code, storage);
        }
    }


    private static Entry toEntry(Account account) {
        Entry e = new Entry();
        e.type    = account.type().name();
        e.balance = account.balance().getAsBigInteger().toString();
        e.nonce   = account.nonce();
        e.code    = account.code().toHexString();
        account.storage().forEach((k, v) ->
                e.storage.put(k.toHexString(), v.toHexString()));
        return e;
    }

    private static Account fromEntry(String addressHex, Entry e) {
        Address address = Address.fromHexString(addressHex);
        AccountType type = AccountType.valueOf(e.type);
        Wei balance = Wei.of(new BigInteger(e.balance));
        Bytes code = e.code.isEmpty() ? Bytes.EMPTY : Bytes.fromHexString(e.code);
        Map<UInt256, UInt256> storage = new HashMap<>();
        e.storage.forEach((k, v) ->
                storage.put(UInt256.fromHexString(k), UInt256.fromHexString(v)));
        return new Account(type, address, balance, e.nonce, code, storage);
    }

    private static String key(Address address) {
        return address.toHexString().toLowerCase();
    }


    public Account getAccount(Address address) {
        Entry e = accounts.get(key(address));
        return e == null ? null : fromEntry(key(address), e);
    }

    public void putAccount(Account account) {
        accounts.put(key(account.address()), toEntry(account));
    }


    public void createEoa(Address address, Wei balance) {
        putAccount(new Account(AccountType.EOA, address, balance, 0L,
                Bytes.EMPTY, Collections.emptyMap()));
    }

    public boolean removeAccount(Address address) {
        return accounts.remove(key(address)) != null;
    }

    public boolean hasAccount(Address address) {
        return accounts.containsKey(key(address));
    }

    public int size() {
        return accounts.size();
    }


    public List<Address> addresses() {
        List<Address> result = new ArrayList<>(accounts.size());
        for (String hex : accounts.keySet()) {
            result.add(Address.fromHexString(hex));
        }
        return Collections.unmodifiableList(result);
    }

 
    /**
     * Returns an immutable snapshot of the current state. Pass to
     * {@link #rollback(WorldStateSnapshot)} to revert a failed transaction.
     */
    public WorldStateSnapshot snapshot() {
        TreeMap<String, Entry> copy = new TreeMap<>();
        for (Map.Entry<String, Entry> me : accounts.entrySet()) {
            Entry src = me.getValue();
            Entry dst = new Entry();
            dst.type    = src.type;
            dst.balance = src.balance;
            dst.nonce   = src.nonce;
            dst.code    = src.code;
            dst.storage = new TreeMap<>(src.storage);
            copy.put(me.getKey(), dst);
        }
        return new WorldStateSnapshot(copy);
    }

    /**
     * Restores the state to the point captured by {@code snapshot}, discarding
     * any changes made after the snapshot was taken.
     */
    public void rollback(WorldStateSnapshot snapshot) {
        this.accounts = snapshot.accountsCopy();
    }


    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(this);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("WorldState serialization failed", e);
        }
    }

    /**
     * Reconstructs a {@link WorldState} from bytes previously produced by
     * {@link #serialize()}.
     */
    public static WorldState deserialize(byte[] data) {
        try (ObjectInputStream ois =
                     new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (WorldState) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("WorldState deserialization failed", e);
        }
    }


    @Override
    public String toString() {
        return "WorldState{accounts=" + accounts.size() + ", addresses=" + accounts.keySet() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorldState ws)) return false;
        return accounts.equals(ws.accounts);
    }

    @Override
    public int hashCode() {
        return accounts.hashCode();
    }
}
