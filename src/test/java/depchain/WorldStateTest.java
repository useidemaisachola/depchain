package depchain;

import depchain.blockchain.Account;
import depchain.blockchain.AccountType;
import depchain.blockchain.WorldState;
import depchain.blockchain.WorldStateSnapshot;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorldState:
 *  1. CRUD operations
 *  2. Snapshot / rollback
 *  3. Serialization (round-trip and determinism)
 */
class WorldStateTest {

    private static final Address ALICE =
            Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address BOB =
            Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Address CAROL =
            Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    private WorldState state;

    @BeforeEach
    void setUp() {
        state = new WorldState();
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @Test
    void getUnknownAddressReturnsNull() {
        assertNull(state.getAccount(ALICE));
    }

    @Test
    void hasAccountReturnsFalseForUnknown() {
        assertFalse(state.hasAccount(ALICE));
    }

    @Test
    void createEoaThenGet() {
        state.createEoa(ALICE, Wei.of(1000L));

        Account account = state.getAccount(ALICE);
        assertNotNull(account);
        assertEquals(AccountType.EOA, account.type());
        assertEquals(Wei.of(1000L), account.balance());
        assertEquals(0L, account.nonce());
        assertTrue(account.code().isEmpty());
        assertTrue(account.storage().isEmpty());
    }

    @Test
    void putAccountThenGetReturnsUpdatedValues() {
        state.createEoa(ALICE, Wei.of(500L));

        Account updated = new Account(AccountType.EOA, ALICE, Wei.of(750L),
                1L, Bytes.EMPTY, Collections.emptyMap());
        state.putAccount(updated);

        Account retrieved = state.getAccount(ALICE);
        assertEquals(Wei.of(750L), retrieved.balance());
        assertEquals(1L, retrieved.nonce());
    }

    @Test
    void removeAccountReturnsTrueAndAccountIsGone() {
        state.createEoa(ALICE, Wei.of(100L));
        assertTrue(state.removeAccount(ALICE));
        assertNull(state.getAccount(ALICE));
        assertFalse(state.hasAccount(ALICE));
    }

    @Test
    void removeUnknownAccountReturnsFalse() {
        assertFalse(state.removeAccount(ALICE));
    }

    @Test
    void sizeReflectsNumberOfAccounts() {
        assertEquals(0, state.size());
        state.createEoa(ALICE, Wei.ZERO);
        assertEquals(1, state.size());
        state.createEoa(BOB, Wei.ZERO);
        assertEquals(2, state.size());
        state.removeAccount(ALICE);
        assertEquals(1, state.size());
    }

    @Test
    void hasAccountReturnsTrueAfterCreate() {
        state.createEoa(ALICE, Wei.ZERO);
        assertTrue(state.hasAccount(ALICE));
    }

    @Test
    void addressesAreSortedDeterministically() {
        state.createEoa(CAROL, Wei.ZERO);
        state.createEoa(ALICE, Wei.ZERO);
        state.createEoa(BOB,   Wei.ZERO);

        List<Address> addrs = state.addresses();
        assertEquals(3, addrs.size());
        // TreeMap key = "0x<hex>"; lexicographic order: alice < bob < carol
        assertEquals(ALICE, addrs.get(0));
        assertEquals(BOB,   addrs.get(1));
        assertEquals(CAROL, addrs.get(2));
    }

    @Test
    void contractAccountStoredAndRetrievedCorrectly() {
        Map<UInt256, UInt256> storage = Map.of(UInt256.ZERO, UInt256.valueOf(42L));
        Account contract = new Account(AccountType.CONTRACT,
                ALICE, Wei.of(0L), 0L, Bytes.fromHexString("0x6001"), storage);
        state.putAccount(contract);

        Account retrieved = state.getAccount(ALICE);
        assertEquals(AccountType.CONTRACT, retrieved.type());
        assertEquals(Bytes.fromHexString("0x6001"), retrieved.code());
        assertEquals(UInt256.valueOf(42L), retrieved.storage().get(UInt256.ZERO));
    }

    // -------------------------------------------------------------------------
    // Snapshot / Rollback
    // -------------------------------------------------------------------------

    @Test
    void rollbackRestoresStateAfterModification() {
        state.createEoa(ALICE, Wei.of(1000L));
        WorldStateSnapshot snap = state.snapshot();

        // Modify state
        state.putAccount(new Account(AccountType.EOA, ALICE,
                Wei.of(500L), 1L, Bytes.EMPTY, Collections.emptyMap()));
        state.createEoa(BOB, Wei.of(200L));

        // Rollback
        state.rollback(snap);

        // Original state restored
        assertEquals(Wei.of(1000L), state.getAccount(ALICE).balance());
        assertEquals(0L, state.getAccount(ALICE).nonce());
        assertFalse(state.hasAccount(BOB));
        assertEquals(1, state.size());
    }

    @Test
    void rollbackAfterAccountDeletion() {
        state.createEoa(ALICE, Wei.of(999L));
        WorldStateSnapshot snap = state.snapshot();

        state.removeAccount(ALICE);
        assertFalse(state.hasAccount(ALICE));

        state.rollback(snap);
        assertTrue(state.hasAccount(ALICE));
        assertEquals(Wei.of(999L), state.getAccount(ALICE).balance());
    }

    @Test
    void snapshotDoesNotAliasLiveState() {
        state.createEoa(ALICE, Wei.of(100L));
        WorldStateSnapshot snap = state.snapshot();

        // Mutate live state
        state.putAccount(new Account(AccountType.EOA, ALICE,
                Wei.of(999L), 5L, Bytes.EMPTY, Collections.emptyMap()));

        // Rollback should bring back 100, not 999
        state.rollback(snap);
        assertEquals(Wei.of(100L), state.getAccount(ALICE).balance());
    }

    @Test
    void multipleSnapshotsAreIndependent() {
        state.createEoa(ALICE, Wei.of(100L));
        WorldStateSnapshot snap1 = state.snapshot();

        state.putAccount(new Account(AccountType.EOA, ALICE,
                Wei.of(200L), 0L, Bytes.EMPTY, Collections.emptyMap()));
        WorldStateSnapshot snap2 = state.snapshot();

        state.putAccount(new Account(AccountType.EOA, ALICE,
                Wei.of(300L), 0L, Bytes.EMPTY, Collections.emptyMap()));

        state.rollback(snap2);
        assertEquals(Wei.of(200L), state.getAccount(ALICE).balance());

        state.rollback(snap1);
        assertEquals(Wei.of(100L), state.getAccount(ALICE).balance());
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    @Test
    void serializeAndDeserializeRoundTrip() {
        state.createEoa(ALICE, Wei.of(1000L));
        state.createEoa(BOB,   Wei.of(500L));
        Map<UInt256, UInt256> storage = Map.of(UInt256.ONE, UInt256.valueOf(7L));
        state.putAccount(new Account(AccountType.CONTRACT, CAROL,
                Wei.of(0L), 2L, Bytes.fromHexString("0x6060"), storage));

        byte[] bytes = state.serialize();
        WorldState restored = WorldState.deserialize(bytes);

        assertEquals(3, restored.size());
        assertEquals(Wei.of(1000L), restored.getAccount(ALICE).balance());
        assertEquals(AccountType.EOA, restored.getAccount(ALICE).type());
        assertEquals(Wei.of(500L), restored.getAccount(BOB).balance());

        Account restoredCarol = restored.getAccount(CAROL);
        assertEquals(AccountType.CONTRACT, restoredCarol.type());
        assertEquals(2L, restoredCarol.nonce());
        assertEquals(Bytes.fromHexString("0x6060"), restoredCarol.code());
        assertEquals(UInt256.valueOf(7L), restoredCarol.storage().get(UInt256.ONE));
    }

    @Test
    void serializationIsDeterministic() {
        // Same state built in two different insertion orders
        WorldState stateA = new WorldState();
        stateA.createEoa(ALICE, Wei.of(100L));
        stateA.createEoa(BOB,   Wei.of(200L));

        WorldState stateB = new WorldState();
        stateB.createEoa(BOB,   Wei.of(200L));  // reversed
        stateB.createEoa(ALICE, Wei.of(100L));

        assertArrayEquals(stateA.serialize(), stateB.serialize(),
                "Serialized bytes must be identical regardless of insertion order");
    }

    @Test
    void emptyWorldStateSerializesAndDeserializes() {
        byte[] bytes = state.serialize();
        WorldState restored = WorldState.deserialize(bytes);
        assertEquals(0, restored.size());
    }

    @Test
    void equalsAndHashCodeConsistentWithState() {
        WorldState a = new WorldState();
        WorldState b = new WorldState();
        a.createEoa(ALICE, Wei.of(1L));
        b.createEoa(ALICE, Wei.of(1L));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.createEoa(BOB, Wei.of(2L));
        assertNotEquals(a, b);
    }
}
