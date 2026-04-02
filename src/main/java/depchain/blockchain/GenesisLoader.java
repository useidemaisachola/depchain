package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the genesis block from {@code /genesis.json} (classpath resource).
 *
 * <p>The genesis JSON uses symbolic account names ("node0", "node1", …) which
 * are resolved at runtime to the deterministic EVM addresses derived from the
 * corresponding RSA public keys via {@link EvmService#deriveAddress}.
 *
 * <p>Loading sequence:
 * <ol>
 *   <li>Parse the genesis template.</li>
 *   <li>Resolve symbolic names → actual {@link Address} instances.</li>
 *   <li>Create an {@link EvmService} and fund every genesis account.</li>
 *   <li>Load {@code /contracts/ISTCoin.bin} and execute the deployment
 *       transaction with the designated deployer account.</li>
 *   <li>Build and return a {@link GenesisBlock} with the final world state.</li>
 * </ol>
 */
public class GenesisLoader {

    private static final String GENESIS_RESOURCE   = "/genesis.json";
    private static final String ISTCOIN_BIN_RESOURCE = "/contracts/ISTCoin.bin";

    private GenesisLoader() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads and processes the genesis block using the supplied node public keys.
     *
     * @param nodePublicKeys map of node-index → RSA public key for all nodes
     *                       (node0 … nodeN-1)
     * @return a fully populated {@link Result} containing the genesis block,
     *         an initialized {@link EvmService}, and the deployed ISTCoin address
     * @throws RuntimeException if the genesis template or ISTCoin bytecode cannot
     *                          be read, or if the deployment transaction fails
     */
    public static Result load(Map<Integer, PublicKey> nodePublicKeys) {
        JsonObject template = readTemplate();

        // ── 1. Resolve symbolic account names → EVM addresses ────────────────
        Map<String, Address> nameToAddress = resolveAccounts(nodePublicKeys);

        // ── 2. Build EvmService with initial balances ─────────────────────────
        EvmService evm = new EvmService();
        Map<String, String> accountBalances = parseAccountBalances(template);
        for (Map.Entry<String, String> entry : accountBalances.entrySet()) {
            Address addr   = resolve(entry.getKey(), nameToAddress);
            Wei     balance = Wei.of(new BigInteger(entry.getValue()));
            evm.createAccount(addr, balance);
        }

        // ── 3. Determine deployer and deployment parameters ───────────────────
        String deployerName = template.get("deployer").getAsString();
        Address deployer    = resolve(deployerName, nameToAddress);
        long gasPrice       = template.get("deployment_gas_price").getAsLong();
        long gasLimit       = template.get("deployment_gas_limit").getAsLong();

        // ── 4. Load ISTCoin bytecode and build the deployment Transaction ─────
        String bytecodeHex = loadIstCoinBytecode();
        // ABI-encode constructor arg: initialOwner = deployer address (padded to 32 bytes)
        String constructorArg = "000000000000000000000000" + deployer.toUnprefixedHexString();
        Bytes  initcode       = Bytes.fromHexString(bytecodeHex + constructorArg);

        Transaction deployTx = Transaction.create(
                deployer, null, Wei.ZERO, initcode, gasPrice, gasLimit,
                evm.getNonce(deployer));

        // ── 5. Execute deployment ─────────────────────────────────────────────
        EvmResult deployResult = evm.executeTransaction(deployTx);
        if (!deployResult.success()) {
            throw new RuntimeException("ISTCoin deployment failed during genesis loading");
        }
        Address istCoinAddress = deployResult.deployedAddress()
                .orElseThrow(() -> new RuntimeException(
                        "Deployment succeeded but no contract address in result"));

        // ── 6. Build GenesisBlock ─────────────────────────────────────────────
        GenesisBlock genesis = buildGenesisBlock(
                deployer, initcode, gasPrice, gasLimit, evm, nameToAddress, accountBalances);

        return new Result(genesis, evm, istCoinAddress);
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * The outcome of a successful genesis load.
     *
     * @param block          the fully computed genesis block (hash + state)
     * @param evmService     an {@link EvmService} initialized with genesis accounts
     *                       and the deployed ISTCoin contract
     * @param istCoinAddress the address at which ISTCoin was deployed
     */
    public record Result(GenesisBlock block, EvmService evmService, Address istCoinAddress) {}

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static JsonObject readTemplate() {
        try (InputStream is = GenesisLoader.class.getResourceAsStream(GENESIS_RESOURCE)) {
            if (is == null) {
                throw new RuntimeException("Genesis template not found: " + GENESIS_RESOURCE);
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new Gson().fromJson(json, JsonObject.class);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read genesis template", e);
        }
    }

    private static String loadIstCoinBytecode() {
        try (InputStream is = GenesisLoader.class.getResourceAsStream(ISTCOIN_BIN_RESOURCE)) {
            if (is == null) {
                throw new RuntimeException("ISTCoin bytecode not found: " + ISTCOIN_BIN_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read ISTCoin bytecode", e);
        }
    }

    /**
     * Builds the symbolic-name → address map for all nodes that appear in the
     * genesis template.
     */
    private static Map<String, Address> resolveAccounts(Map<Integer, PublicKey> nodePublicKeys) {
        Map<String, Address> map = new LinkedHashMap<>();
        for (Map.Entry<Integer, PublicKey> entry : nodePublicKeys.entrySet()) {
            String name = "node" + entry.getKey();
            map.put(name, EvmService.deriveAddress(entry.getValue()));
        }
        return map;
    }

    /** Looks up an address by symbolic name; throws if unknown. */
    private static Address resolve(String name, Map<String, Address> nameToAddress) {
        Address addr = nameToAddress.get(name);
        if (addr == null) {
            throw new IllegalArgumentException(
                    "Unknown genesis account name: '" + name + "'. " +
                    "Known names: " + nameToAddress.keySet());
        }
        return addr;
    }

    /** Parses the "accounts" object from the template into a name → balance map. */
    private static Map<String, String> parseAccountBalances(JsonObject template) {
        Map<String, String> balances = new LinkedHashMap<>();
        JsonObject accounts = template.getAsJsonObject("accounts");
        for (Map.Entry<String, JsonElement> e : accounts.entrySet()) {
            balances.put(e.getKey(), e.getValue().getAsString());
        }
        return balances;
    }

    /**
     * Assembles the {@link GenesisBlock} from the post-deployment EVM state.
     *
     * <p>The world state in the returned block reflects the state AFTER the
     * ISTCoin deployment transaction has been executed (as per the project spec).
     */
    private static GenesisBlock buildGenesisBlock(
            Address deployer,
            Bytes   initcode,
            long    gasPrice,
            long    gasLimit,
            EvmService evm,
            Map<String, Address> nameToAddress,
            Map<String, String>  accountBalances) {

        // Transaction entry (deployment)
        GenesisBlock.TransactionEntry txEntry = new GenesisBlock.TransactionEntry(
                deployer.toHexString(),
                null,
                initcode.toHexString(),
                gasPrice,
                gasLimit);

        // World state: snapshot all accounts known to the EVM
        Map<String, GenesisBlock.AccountEntry> state = new LinkedHashMap<>();
        for (Map.Entry<String, Address> e : nameToAddress.entrySet()) {
            if (!accountBalances.containsKey(e.getKey())) continue; // skip unrelated
            Account acc = evm.getAccount(e.getValue());
            if (acc == null) continue;

            Map<String, String> storage = new LinkedHashMap<>();
            acc.storage().forEach((k, v) -> storage.put(k.toHexString(), v.toHexString()));

            state.put(e.getValue().toHexString(), new GenesisBlock.AccountEntry(
                    acc.balance().getAsBigInteger().toString(),
                    acc.nonce(),
                    acc.code().isEmpty() ? "" : acc.code().toHexString(),
                    storage));
        }

        // Include the deployed contract account
        org.hyperledger.besu.datatypes.Address contractAddr =
                org.hyperledger.besu.datatypes.Address.contractAddress(deployer, 0);
        Account contractAcc = evm.getAccount(contractAddr);
        if (contractAcc != null) {
            Map<String, String> storage = new LinkedHashMap<>();
            contractAcc.storage().forEach((k, v) -> storage.put(k.toHexString(), v.toHexString()));
            state.put(contractAddr.toHexString(), new GenesisBlock.AccountEntry(
                    contractAcc.balance().getAsBigInteger().toString(),
                    contractAcc.nonce(),
                    contractAcc.code().toHexString(),
                    storage));
        }

        return new GenesisBlock(List.of(txEntry), state);
    }
}
