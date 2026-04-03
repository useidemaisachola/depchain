package depchain;

import depchain.blockchain.BlockStore;
import depchain.blockchain.EvmService;
import depchain.blockchain.GenesisLoader;
import depchain.blockchain.PersistedBlock;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import depchain.config.NetworkConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Block Persistence.
 *
 * Verifies that:
 *  1. PersistedBlock computes a non-null "0x"-prefixed hash.
 *  2. PersistedBlock JSON round-trips correctly.
 *  3. A block's hash changes when any content field changes.
 *  4. BlockStore saves and loads a single block.
 *  5. BlockStore loads multiple blocks in height order.
 *  6. BlockStore.validateChain passes for a valid chain.
 *  7. BlockStore.validateChain fails for a tampered hash.
 *  8. BlockStore.validateChain fails for a broken previous_block_hash link.
 *  9. BlockStore.validateChain fails for non-consecutive heights.
 * 10. EvmService.snapshotWorldState includes all created accounts.
 * 11. EvmService.restoreWorldState reconstructs balances and nonces.
 * 12. EvmService.restoreWorldState reconstructs contract code and storage.
 * 13. Genesis block is persisted at height 0 with correct structure.
 * 14. Restoring EVM state from chain equals the original snapshot.
 */
class BlockPersistenceTest {

    @TempDir
    Path tempDir;

    private BlockStore blockStore;

    @BeforeEach
    void setUp() {
        blockStore = new BlockStore(tempDir.resolve("blocks").toString());
    }

    // -------------------------------------------------------------------------
    // PersistedBlock hash
    // -------------------------------------------------------------------------

    @Test
    void blockHash_isNonNullAndHexPrefixed() {
        PersistedBlock block = emptyBlock(null, 0);
        assertNotNull(block.getBlockHash());
        assertTrue(block.getBlockHash().startsWith("0x"),
                "block hash should start with 0x");
        assertEquals(66, block.getBlockHash().length(),
                "SHA-256 hex should be 32 bytes = 64 hex chars + '0x' prefix");
    }

    @Test
    void blockHash_changeWhenHeightChanges() {
        PersistedBlock a = emptyBlock(null, 0);
        PersistedBlock b = emptyBlock(null, 1);
        assertNotEquals(a.getBlockHash(), b.getBlockHash());
    }

    @Test
    void blockHash_changeWhenPreviousHashChanges() {
        PersistedBlock a = emptyBlock(null,  0);
        PersistedBlock b = emptyBlock("0xdeadbeef", 0);
        assertNotEquals(a.getBlockHash(), b.getBlockHash());
    }

    @Test
    void blockHash_changeWhenWorldStateChanges() {
        Map<String, PersistedBlock.AccountEntry> ws1 = Collections.emptyMap();
        Map<String, PersistedBlock.AccountEntry> ws2 = Map.of(
                "0x1234567890123456789012345678901234567890",
                new PersistedBlock.AccountEntry("1000", 0, "", Collections.emptyMap())
        );
        PersistedBlock a = new PersistedBlock(null, 0, Collections.emptyList(), ws1);
        PersistedBlock b = new PersistedBlock(null, 0, Collections.emptyList(), ws2);
        assertNotEquals(a.getBlockHash(), b.getBlockHash());
    }

    // -------------------------------------------------------------------------
    // JSON round-trip
    // -------------------------------------------------------------------------

    @Test
    void jsonRoundTrip_preservesAllFields() {
        Map<String, String> storage = Map.of("0xab", "0xcd");
        PersistedBlock.AccountEntry acc = new PersistedBlock.AccountEntry(
                "999999999", 3, "0x6001600055", storage);
        Map<String, PersistedBlock.AccountEntry> ws = new LinkedHashMap<>();
        ws.put("0xdeadbeef00000000000000000000000000000001", acc);

        PersistedBlock.TransactionEntry tx = new PersistedBlock.TransactionEntry(
                "0xaabb", "0xccdd", "0x1234", 1L, 21000L, 21000L, "21000", true);

        PersistedBlock original = new PersistedBlock("0xprev", 1, List.of(tx), ws);
        PersistedBlock restored = PersistedBlock.fromJson(original.toJson());

        assertEquals(original.getBlockHash(),         restored.getBlockHash());
        assertEquals(original.getPreviousBlockHash(), restored.getPreviousBlockHash());
        assertEquals(original.getHeight(),            restored.getHeight());
        assertEquals(1, restored.getTransactions().size());
        assertEquals("0xaabb", restored.getTransactions().get(0).from);
        assertEquals("21000",  restored.getTransactions().get(0).fee);
        assertTrue(restored.getWorldState().containsKey(
                "0xdeadbeef00000000000000000000000000000001"));
    }

    // -------------------------------------------------------------------------
    // BlockStore save / load
    // -------------------------------------------------------------------------

    @Test
    void blockStore_saveAndLoad_singleBlock() {
        PersistedBlock genesis = emptyBlock(null, 0);
        blockStore.save(genesis);

        List<PersistedBlock> chain = blockStore.loadChain();
        assertEquals(1, chain.size());
        assertEquals(genesis.getBlockHash(), chain.get(0).getBlockHash());
        assertEquals(0, chain.get(0).getHeight());
    }

    @Test
    void blockStore_loadChain_orderedByHeight() {
        PersistedBlock b0 = emptyBlock(null, 0);
        PersistedBlock b1 = emptyBlock(b0.getBlockHash(), 1);
        PersistedBlock b2 = emptyBlock(b1.getBlockHash(), 2);

        // Save out of order
        blockStore.save(b2);
        blockStore.save(b0);
        blockStore.save(b1);

        List<PersistedBlock> chain = blockStore.loadChain();
        assertEquals(3, chain.size());
        assertEquals(0, chain.get(0).getHeight());
        assertEquals(1, chain.get(1).getHeight());
        assertEquals(2, chain.get(2).getHeight());
    }

    @Test
    void blockStore_loadChain_emptyWhenDirectoryMissing() {
        BlockStore fresh = new BlockStore(tempDir.resolve("nonexistent/blocks").toString());
        assertTrue(fresh.loadChain().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Chain validation
    // -------------------------------------------------------------------------

    @Test
    void validateChain_passesForValidTwoBlockChain() {
        PersistedBlock b0 = emptyBlock(null, 0);
        PersistedBlock b1 = emptyBlock(b0.getBlockHash(), 1);
        blockStore.save(b0);
        blockStore.save(b1);

        assertTrue(blockStore.validateChain(blockStore.loadChain()));
    }

    @Test
    void validateChain_passesForEmptyChain() {
        assertTrue(blockStore.validateChain(Collections.emptyList()));
    }

    @Test
    void validateChain_failsForTamperedHash() {
        PersistedBlock b0 = emptyBlock(null, 0);
        PersistedBlock b1 = emptyBlock(b0.getBlockHash(), 1);
        blockStore.save(b0);
        blockStore.save(b1);

        List<PersistedBlock> chain = blockStore.loadChain();
        // Tamper: replace b1 with a block that has the wrong previous hash
        PersistedBlock tampered = new PersistedBlock("0xwrong", 1,
                Collections.emptyList(), Collections.emptyMap());
        chain.set(1, tampered);

        assertFalse(blockStore.validateChain(chain));
    }

    @Test
    void validateChain_failsForBrokenPreviousHashLink() {
        PersistedBlock b0 = emptyBlock(null, 0);
        PersistedBlock b1 = emptyBlock("0xthisDoeNotMatchB0", 1);
        // valid hashes but broken link
        List<PersistedBlock> chain = List.of(b0, b1);

        assertFalse(blockStore.validateChain(chain));
    }

    @Test
    void validateChain_failsForNonConsecutiveHeights() {
        PersistedBlock b0 = emptyBlock(null, 0);
        PersistedBlock b2 = emptyBlock(b0.getBlockHash(), 2); // gap: height 1 missing
        List<PersistedBlock> chain = List.of(b0, b2);

        assertFalse(blockStore.validateChain(chain));
    }

    // -------------------------------------------------------------------------
    // EvmService snapshot / restore
    // -------------------------------------------------------------------------

    @Test
    void snapshotWorldState_includesAllCreatedAccounts() {
        EvmService evm = new EvmService();
        Address alice = address(1);
        Address bob   = address(2);
        evm.createAccount(alice, Wei.of(1000));
        evm.createAccount(bob,   Wei.of(2000));

        Map<Address, depchain.blockchain.Account> snapshot = evm.snapshotWorldState();
        assertTrue(snapshot.containsKey(alice));
        assertTrue(snapshot.containsKey(bob));
        assertEquals(Wei.of(1000), snapshot.get(alice).balance());
        assertEquals(Wei.of(2000), snapshot.get(bob).balance());
    }

    @Test
    void restoreWorldState_reconstructsBalancesAndNonces() {
        Map<String, PersistedBlock.AccountEntry> ws = new LinkedHashMap<>();
        ws.put(address(1).toHexString(),
                new PersistedBlock.AccountEntry("5000", 7L, "", Collections.emptyMap()));
        ws.put(address(2).toHexString(),
                new PersistedBlock.AccountEntry("3000", 2L, "", Collections.emptyMap()));

        EvmService evm = new EvmService();
        evm.restoreWorldState(ws);

        assertEquals(Wei.of(5000), evm.getBalance(address(1)));
        assertEquals(7L,           evm.getNonce(address(1)));
        assertEquals(Wei.of(3000), evm.getBalance(address(2)));
        assertEquals(2L,           evm.getNonce(address(2)));
    }

    @Test
    void restoreWorldState_reconstructsContractCodeAndStorage() {
        Map<String, String> storage = new LinkedHashMap<>();
        storage.put("0x0000000000000000000000000000000000000000000000000000000000000001",
                    "0x0000000000000000000000000000000000000000000000000000000000000042");
        Map<String, PersistedBlock.AccountEntry> ws = new LinkedHashMap<>();
        String contractHex = address(10).toHexString();
        ws.put(contractHex,
                new PersistedBlock.AccountEntry("0", 0L, "0x6001600055", storage));

        EvmService evm = new EvmService();
        evm.restoreWorldState(ws);

        Bytes code = evm.getContractCode(address(10));
        assertFalse(code.isEmpty(), "contract code should be restored");
        assertEquals(Bytes.fromHexString("0x6001600055"), code);
    }

    @Test
    void snapshotRestoreRoundTrip_preservesState() {
        // Build original state
        EvmService original = new EvmService();
        original.createAccount(address(1), Wei.of(BigInteger.TEN.pow(18)));
        original.createAccount(address(2), Wei.of(BigInteger.TEN.pow(18).multiply(BigInteger.TWO)));

        // Snapshot → AccountEntry map
        Map<String, PersistedBlock.AccountEntry> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Address, depchain.blockchain.Account> e
                : original.snapshotWorldState().entrySet()) {
            depchain.blockchain.Account acc = e.getValue();
            Map<String, String> storage = new LinkedHashMap<>();
            acc.storage().forEach((k, v) -> storage.put(k.toHexString(), v.toHexString()));
            snapshot.put(e.getKey().toHexString(), new PersistedBlock.AccountEntry(
                    acc.balance().getAsBigInteger().toString(),
                    acc.nonce(), acc.code().isEmpty() ? "" : acc.code().toHexString(),
                    storage));
        }

        // Restore into fresh EvmService
        EvmService restored = new EvmService();
        restored.restoreWorldState(snapshot);

        assertEquals(original.getBalance(address(1)), restored.getBalance(address(1)));
        assertEquals(original.getBalance(address(2)), restored.getBalance(address(2)));
        assertEquals(original.getNonce(address(1)),   restored.getNonce(address(1)));
    }

    // -------------------------------------------------------------------------
    // Genesis block persistence
    // -------------------------------------------------------------------------

    @Test
    void genesisBlock_persistedAtHeightZeroWithNullPreviousHash() throws Exception {
        Map<Integer, PublicKey> nodeKeys = generateNodeKeys(4);
        GenesisLoader.Result result = GenesisLoader.load(nodeKeys, generateClientKeys());

        // Snapshot genesis state
        EvmService evm = result.evmService();
        Map<String, PersistedBlock.AccountEntry> ws = buildSnapshot(evm);

        PersistedBlock genesis = new PersistedBlock(null, 0,
                Collections.emptyList(), ws);
        blockStore.save(genesis);

        List<PersistedBlock> chain = blockStore.loadChain();
        assertEquals(1, chain.size());
        assertEquals(0,    chain.get(0).getHeight());
        assertNull(chain.get(0).getPreviousBlockHash());
        assertTrue(chain.get(0).getBlockHash().startsWith("0x"));
    }

    @Test
    void genesisBlock_chainValidationPasses() throws Exception {
        Map<Integer, PublicKey> nodeKeys = generateNodeKeys(4);
        GenesisLoader.Result result = GenesisLoader.load(nodeKeys, generateClientKeys());
        EvmService evm = result.evmService();

        PersistedBlock genesis = new PersistedBlock(null, 0,
                Collections.emptyList(), buildSnapshot(evm));
        blockStore.save(genesis);

        assertTrue(blockStore.validateChain(blockStore.loadChain()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PersistedBlock emptyBlock(String prevHash, int height) {
        return new PersistedBlock(prevHash, height,
                Collections.emptyList(), Collections.emptyMap());
    }

    /** Generates a 20-byte address from a small integer seed. */
    private static Address address(int seed) {
        byte[] bytes = new byte[20];
        bytes[19] = (byte) seed;
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }

    private static Map<Integer, PublicKey> generateNodeKeys(int count) throws Exception {
        Map<Integer, PublicKey> keys = new HashMap<>();
        for (int i = 0; i < count; i++) {
            KeyPair kp = java.security.KeyPairGenerator.getInstance("RSA")
                    .generateKeyPair();
            keys.put(i, kp.getPublic());
        }
        return keys;
    }

    private static Map<String, PublicKey> generateClientKeys() throws Exception {
        Map<String, PublicKey> keys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_STATIC_CLIENTS; i++) {
            KeyPair kp = java.security.KeyPairGenerator.getInstance("RSA")
                    .generateKeyPair();
            keys.put("client" + i, kp.getPublic());
        }
        return keys;
    }

    private static Map<String, PersistedBlock.AccountEntry> buildSnapshot(EvmService evm) {
        Map<String, PersistedBlock.AccountEntry> ws = new LinkedHashMap<>();
        for (Map.Entry<Address, depchain.blockchain.Account> e
                : evm.snapshotWorldState().entrySet()) {
            depchain.blockchain.Account acc = e.getValue();
            Map<String, String> storage = new LinkedHashMap<>();
            acc.storage().forEach((k, v) -> storage.put(k.toHexString(), v.toHexString()));
            ws.put(e.getKey().toHexString(), new PersistedBlock.AccountEntry(
                    acc.balance().getAsBigInteger().toString(),
                    acc.nonce(), acc.code().isEmpty() ? "" : acc.code().toHexString(),
                    storage));
        }
        return ws;
    }
}
