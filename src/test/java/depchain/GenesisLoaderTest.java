package depchain;

import depchain.blockchain.GenesisBlock;
import depchain.blockchain.GenesisLoader;
import depchain.crypto.CryptoUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import depchain.config.NetworkConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for genesis block loading (Issue #8).
 *
 * Verifies that GenesisLoader:
 *  1. Reads genesis.json and resolves symbolic account names to EVM addresses.
 *  2. Funds each genesis account with the configured DepCoin balance.
 *  3. Successfully deploys the ISTCoin ERC-20 contract.
 *  4. Returns a non-null block hash and null previous_block_hash.
 *  5. Produces deterministic output for the same key set.
 *  6. The genesis JSON round-trips correctly.
 */
class GenesisLoaderTest {

    private static final int NUM_NODES = 4;
    // Balance from genesis.json: 1 ETH-equivalent in Wei
    private static final BigInteger INITIAL_BALANCE = new BigInteger("1000000000000000000");

    private Map<Integer, PublicKey> nodePublicKeys;
    private Map<Integer, Address>   nodeAddresses;
    private Map<String, PublicKey>  clientPublicKeys;

    @BeforeEach
    void setUp() {
        // Generate fresh RSA key pairs for the four genesis nodes.
        nodePublicKeys = new HashMap<>();
        nodeAddresses  = new HashMap<>();
        for (int i = 0; i < NUM_NODES; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            nodePublicKeys.put(i, kp.getPublic());
            nodeAddresses.put(i, depchain.blockchain.EvmService.deriveAddress(kp.getPublic()));
        }

        // Stage 2 requirement: static known clients. The genesis template funds
        // client0..clientN, so tests must provide explicit keys (no fallback).
        clientPublicKeys = new HashMap<>();
        for (int i = 0; i < NetworkConfig.NUM_STATIC_CLIENTS; i++) {
            KeyPair kp = CryptoUtils.generateKeyPair();
            clientPublicKeys.put("client" + i, kp.getPublic());
        }
    }

    // -------------------------------------------------------------------------
    // 1. Successful load
    // -------------------------------------------------------------------------

    @Test
    void load_succeeds() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        assertNotNull(result);
        assertNotNull(result.block());
        assertNotNull(result.evmService());
        assertNotNull(result.istCoinAddress());
    }

    // -------------------------------------------------------------------------
    // 2. Block hash and previous_block_hash
    // -------------------------------------------------------------------------

    @Test
    void genesisBlock_hasNonNullHash() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        String hash = result.block().getBlockHash();
        assertNotNull(hash, "block_hash must not be null");
        assertTrue(hash.startsWith("0x"), "block_hash must be a hex string");
        assertTrue(hash.length() > 2, "block_hash must be non-empty");
    }

    @Test
    void genesisBlock_previousHashIsNull() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        assertNull(result.block().getPreviousBlockHash(),
                "previous_block_hash must be null for genesis");
    }

    // -------------------------------------------------------------------------
    // 3. Initial DepCoin balances
    // -------------------------------------------------------------------------

    @Test
    void allGenesisAccountsFunded() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        for (int i = 0; i < NUM_NODES; i++) {
            Address addr    = nodeAddresses.get(i);
            Wei     balance = result.evmService().getBalance(addr);
            // After genesis, balance = initial - deployment_fee (node0 is deployer).
            // For non-deployers: balance == INITIAL_BALANCE.
            // For deployer (node0): balance < INITIAL_BALANCE (fee deducted).
            assertTrue(balance.getAsBigInteger().compareTo(BigInteger.ZERO) >= 0,
                    "node" + i + " balance must be non-negative");
        }
    }

    @Test
    void nonDeployerBalancesUntouched() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        // node1, node2, node3 are not the deployer; their balances should be
        // exactly the initial amount configured in genesis.json.
        for (int i = 1; i < NUM_NODES; i++) {
            Wei balance = result.evmService().getBalance(nodeAddresses.get(i));
            assertEquals(INITIAL_BALANCE, balance.getAsBigInteger(),
                    "node" + i + " balance must equal initial genesis allocation");
        }
    }

    @Test
    void deployerBalanceReducedByFee() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        // node0 is the deployer; its balance must be < initial (fee was charged).
        Wei deployerBalance = result.evmService().getBalance(nodeAddresses.get(0));
        assertTrue(deployerBalance.getAsBigInteger().compareTo(INITIAL_BALANCE) < 0,
                "deployer balance must be reduced by the deployment fee");
    }

    // -------------------------------------------------------------------------
    // 4. ISTCoin deployment
    // -------------------------------------------------------------------------

    @Test
    void istCoinContractDeployed() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        Address addr = result.istCoinAddress();
        Bytes code   = result.evmService().getContractCode(addr);
        assertFalse(code.isEmpty(), "ISTCoin must have non-empty runtime bytecode");
    }

    @Test
    void istCoinNonces_startAtZero_forNonDeployer() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        // Non-deployer nodes must have nonce 0.
        for (int i = 1; i < NUM_NODES; i++) {
            assertEquals(0L, result.evmService().getNonce(nodeAddresses.get(i)),
                    "node" + i + " nonce must be 0 (no transactions sent)");
        }
    }

    @Test
    void deployerNonce_incrementedAfterDeployment() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        // node0 sent exactly one transaction (the deployment), so nonce must be 1.
        assertEquals(1L, result.evmService().getNonce(nodeAddresses.get(0)),
                "deployer nonce must be 1 after the genesis deployment transaction");
    }

    // -------------------------------------------------------------------------
    // 5. Determinism
    // -------------------------------------------------------------------------

    @Test
    void sameKeys_produceSameGenesisHash() {
        GenesisLoader.Result r1 = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        GenesisLoader.Result r2 = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        assertEquals(r1.block().getBlockHash(), r2.block().getBlockHash(),
                "Genesis block hash must be deterministic for the same key set");
    }

    // -------------------------------------------------------------------------
    // 6. Genesis block includes deployment transaction
    // -------------------------------------------------------------------------

    @Test
    void genesisBlock_hasDeploymentTransaction() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        GenesisBlock block = result.block();
        assertEquals(1, block.getTransactions().size(),
                "Genesis block must contain exactly one transaction (ISTCoin deployment)");

        GenesisBlock.TransactionEntry tx = block.getTransactions().get(0);
        assertNull(tx.to, "Deployment transaction 'to' must be null");
        assertNotNull(tx.from, "Deployment transaction must have a 'from' address");
        assertNotNull(tx.input, "Deployment transaction must have initcode in 'input'");
        assertTrue(tx.input.startsWith("0x"), "initcode must be hex-encoded");
        assertTrue(tx.gasPrice > 0, "gasPrice must be > 0");
        assertTrue(tx.gasLimit > 0, "gasLimit must be > 0");
    }

    // -------------------------------------------------------------------------
    // 7. JSON round-trip
    // -------------------------------------------------------------------------

    @Test
    void genesisBlock_jsonRoundTrip() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        GenesisBlock original = result.block();

        String json       = original.toJson();
        GenesisBlock copy = GenesisBlock.fromJson(json);

        assertEquals(original.getBlockHash(),         copy.getBlockHash());
        assertNull(copy.getPreviousBlockHash());
        assertEquals(original.getTransactions().size(), copy.getTransactions().size());
        assertEquals(original.getState().size(),         copy.getState().size());
    }

    // -------------------------------------------------------------------------
    // 8. State contains all genesis accounts
    // -------------------------------------------------------------------------

    @Test
    void genesisBlock_stateContainsAllNodeAccounts() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        Map<String, GenesisBlock.AccountEntry> state = result.block().getState();
        for (int i = 0; i < NUM_NODES; i++) {
            String hex = nodeAddresses.get(i).toHexString();
            assertTrue(state.containsKey(hex),
                    "state must include node" + i + " address " + hex);
        }
    }

    @Test
    void genesisBlock_stateContainsIstCoinContract() {
        GenesisLoader.Result result = GenesisLoader.load(nodePublicKeys, clientPublicKeys);
        Map<String, GenesisBlock.AccountEntry> state = result.block().getState();
        String contractHex = result.istCoinAddress().toHexString();
        assertTrue(state.containsKey(contractHex),
                "state must include the deployed ISTCoin contract");
        GenesisBlock.AccountEntry contractEntry = state.get(contractHex);
        assertFalse(contractEntry.code.isEmpty(),
                "ISTCoin contract entry must have non-empty code");
    }
}
