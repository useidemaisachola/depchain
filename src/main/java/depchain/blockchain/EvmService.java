package depchain.blockchain;

import depchain.crypto.CryptoUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.math.BigInteger;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Thin wrapper around the Hyperledger Besu standalone EVM.
 *
 * Maintains an in-memory world state (accounts, balances, contract code/storage)
 * backed directly by a {@link SimpleWorld} instance.
 *
 * Transaction isolation: each call/deployment works on a snapshot ({@code SimpleWorld}
 * child) and commits to the root world only on success. Reverted executions leave the
 * root world unchanged.
 */
public class EvmService {

    /** Root world state. Committed changes are visible to all subsequent executions. */
    final SimpleWorld world;
    private final Map<Address, Long> nonces = new HashMap<>();

    /**
     * Tracks every address that has ever been created in this world state, in
     * insertion order. Used by {@link #snapshotWorldState()} to produce a
     * deterministic, complete account listing for block persistence.
     */
    private final Set<Address> knownAddresses = new LinkedHashSet<>();

    public EvmService() {
        this.world = new SimpleWorld();
    }

    // -------------------------------------------------------------------------
    // Account management
    // -------------------------------------------------------------------------

    /** Create an EOA with the given balance (in Wei). */
    public void createAccount(Address address, Wei balance) {
        world.createAccount(address, 0, balance);
        nonces.put(address, 0L);
        knownAddresses.add(address);
    }

    public Wei getBalance(Address address) {
        var account = world.get(address);
        return account != null ? account.getBalance() : Wei.ZERO;
    }

    public long getNonce(Address address) {
        return nonces.getOrDefault(address, 0L);
    }

    public Bytes getContractCode(Address address) {
        var account = world.get(address);
        return (account != null) ? account.getCode() : Bytes.EMPTY;
    }

    // -------------------------------------------------------------------------
    // Contract deployment
    // -------------------------------------------------------------------------

    /**
     * Deploys a contract by executing its initcode.
     *
     * Uses a {@link SimpleWorld} child for execution isolation; commits to the root
     * world only when the constructor returns successfully.
     *
     * @param sender   deploying account address
     * @param initcode constructor bytecode + ABI-encoded constructor arguments
     * @param gasLimit maximum gas to spend
     * @return address at which the contract was deployed
     * @throws RuntimeException if deployment fails (revert or exceptional halt)
     */
    public Address deployContract(Address sender, Bytes initcode, long gasLimit) {
        long nonce = nonces.getOrDefault(sender, 0L);
        Address contractAddress = Address.contractAddress(sender, nonce);

        EVMExecutor.cancun(EvmConfiguration.DEFAULT)
                .messageFrameType(MessageFrame.Type.CONTRACT_CREATION)
                .code(initcode)
                .sender(sender)
                .receiver(contractAddress)
                .contract(contractAddress)
                .gas(gasLimit)
                .worldUpdater(world)
                .commitWorldState()
                .execute();

        // Verify the contract was created (non-empty runtime bytecode stored)
        var contractAccount = world.get(contractAddress);
        if (contractAccount == null || contractAccount.getCode().isEmpty()) {
            throw new RuntimeException("Contract deployment failed at address " + contractAddress);
        }

        nonces.put(sender, nonce + 1);
        return contractAddress;
    }

    // -------------------------------------------------------------------------
    // Contract call
    // -------------------------------------------------------------------------

    /**
     * Calls a deployed contract function.
     *
     * Returns raw EVM output bytes:
     * <ul>
     *   <li>normal return — ABI-encoded return values</li>
     *   <li>revert with reason — starts with {@code 0x08c379a0} (Error(string) selector)</li>
     *   <li>exceptional halt — {@link Bytes#EMPTY}</li>
     * </ul>
     *
     * State changes are committed to the root world only when the call succeeds.
     *
     * @param caller   calling account address
     * @param contract contract address
     * @param callData ABI-encoded function selector + arguments
     * @param gasLimit maximum gas to spend
     * @return raw output bytes from the EVM
     */
    public Bytes callContract(Address caller, Address contract, Bytes callData, long gasLimit) {
        var contractAccount = world.get(contract);
        if (contractAccount == null) {
            throw new RuntimeException("No contract at address: " + contract);
        }

        // EVMExecutor.execute() returns frame.getReturnData(), which is always Bytes.EMPTY
        // for root frames in Besu 24.x (returnData is only set when a child frame returns to
        // a parent). The actual output lives in frame.getOutputData(); capture it via a tracer.
        Bytes[] output = {Bytes.EMPTY};
        OperationTracer tracer = new OperationTracer() {
            @Override
            public void traceContextExit(MessageFrame frame) {
                if (frame.getDepth() == 0) {
                    output[0] = frame.getOutputData();
                }
            }
        };

        EVMExecutor.cancun(EvmConfiguration.DEFAULT)
                .code(contractAccount.getCode())
                .sender(caller)
                .receiver(contract)
                .contract(contract)
                .gas(gasLimit)
                .callData(callData)
                .worldUpdater(world)
                .commitWorldState()
                .tracer(tracer)
                .execute();

        return output[0];
    }

    // -------------------------------------------------------------------------
    // Account model
    // -------------------------------------------------------------------------

    /**
     * Returns an immutable {@link Account} snapshot for the given address.
     *
     * Type rule: empty code → {@link AccountType#EOA}; non-empty code →
     * {@link AccountType#CONTRACT}.
     *
     * The storage map contains only the slots that have been written since
     * deployment; unwritten slots are absent (their value is implicitly zero).
     *
     * @return the account snapshot, or {@code null} if no account exists at
     *         the address
     */
    public Account getAccount(Address address) {
        var raw = world.get(address);
        if (raw == null) return null;

        Bytes code = raw.getCode();
        if (code == null) code = Bytes.EMPTY;

        AccountType type = code.isEmpty() ? AccountType.EOA : AccountType.CONTRACT;
        long nonce = getNonce(address);

        Map<UInt256, UInt256> storage;
        if (type == AccountType.CONTRACT) {
            // SimpleWorld always stores SimpleAccount instances; this cast is safe.
            storage = ((SimpleAccount) raw).getUpdatedStorage();
        } else {
            storage = Collections.emptyMap();
        }

        return new Account(type, address, raw.getBalance(), nonce, code, storage);
    }

    // -------------------------------------------------------------------------
    // Transaction execution
    // -------------------------------------------------------------------------

    /** Base gas cost for a plain DepCoin transfer (matching Ethereum's flat transfer cost). */
    private static final long GAS_TRANSFER = 21_000L;

    /**
     * Executes a transaction against the current world state, enforcing gas fee rules.
     *
     * <p>Fee rules (per project spec):
     * <ul>
     *   <li>Pre-condition: sender balance ≥ gasPrice × gasLimit + value; otherwise rejected
     *       (returns {@code success=false}, no fee charged).</li>
     *   <li>Fee = gasPrice × min(gasLimit, gasUsed), always deducted from the sender's
     *       DepCoin balance — even when the transaction is aborted.</li>
     *   <li>If gasUsed ≥ gasLimit (out-of-gas): transaction is aborted, full gasLimit is
     *       charged (no refund).</li>
     * </ul>
     *
     * <p>The sender's nonce is incremented for every processed transaction (even failed ones)
     * to prevent replay attacks.
     *
     * @param tx the transaction to execute
     * @return execution result with success flag, output bytes, gasUsed, and fee charged
     */
    public EvmResult executeTransaction(Transaction tx) {
        Address sender   = tx.getFrom();
        long    gasLimit = tx.getGasLimit();
        long    gasPrice = tx.getGasPrice();
        Wei     value    = tx.getValue();
        Wei     maxFee   = Wei.of(gasPrice * gasLimit);

        // Pre-check: sender must cover worst-case fee + any value being transferred.
        if (getBalance(sender).compareTo(maxFee.add(value)) < 0) {
            return new EvmResult(false, Bytes.EMPTY, 0, Wei.ZERO);
        }

        long  currentNonce = nonces.getOrDefault(sender, 0L);
        long  gasUsed;
        Bytes output  = Bytes.EMPTY;
        boolean success;

        if (tx.isTransfer()) {
            // Plain DepCoin transfer — fixed base cost, no EVM involved.
            if (gasLimit < GAS_TRANSFER) {
                // Gas limit too low: out-of-gas, abort and charge the full gasLimit.
                gasUsed = gasLimit;
                success = false;
            } else {
                gasUsed = GAS_TRANSFER;
                Address recipient = tx.getTo().get();
                world.getAccount(sender).decrementBalance(value);
                var recipientMut = world.getAccount(recipient);
                if (recipientMut == null) {
                    world.createAccount(recipient, 0, value);
                } else {
                    recipientMut.incrementBalance(value);
                }
                knownAddresses.add(recipient);
                success = true;
            }
        } else {
            // EVM execution (deployment or contract call).
            // Capture gasUsed, output and success via a context-exit tracer.
            long[]    gasUsedArr = {gasLimit};   // default = OOG (all gas consumed)
            Bytes[]   outputArr  = {Bytes.EMPTY};
            boolean[] successArr = {false};

            OperationTracer tracer = new OperationTracer() {
                @Override
                public void traceContextExit(MessageFrame frame) {
                    if (frame.getDepth() == 0) {
                        gasUsedArr[0] = gasLimit - frame.getRemainingGas();
                        outputArr[0]  = frame.getOutputData();
                        successArr[0] = frame.getState() == MessageFrame.State.COMPLETED_SUCCESS;
                    }
                }
            };

            if (tx.isDeployment()) {
                Address contractAddress = Address.contractAddress(sender, currentNonce);

                EVMExecutor.cancun(EvmConfiguration.DEFAULT)
                        .messageFrameType(MessageFrame.Type.CONTRACT_CREATION)
                        .code(tx.getData())
                        .sender(sender)
                        .receiver(contractAddress)
                        .contract(contractAddress)
                        .ethValue(value)
                        .gas(gasLimit)
                        .worldUpdater(world)
                        .commitWorldState()
                        .tracer(tracer)
                        .execute();

                success = successArr[0];
                gasUsed = gasUsedArr[0];
                // For deployments, output carries the 20-byte contract address so callers
                // can retrieve it via EvmResult.deployedAddress().
                output  = success ? contractAddress : Bytes.EMPTY;
                if (success) {
                    knownAddresses.add(contractAddress);
                }

            } else { // isContractCall()
                Address contractAddress = tx.getTo().get();
                var contractAccount = world.get(contractAddress);

                if (contractAccount != null) {
                    EVMExecutor.cancun(EvmConfiguration.DEFAULT)
                            .code(contractAccount.getCode())
                            .sender(sender)
                            .receiver(contractAddress)
                            .contract(contractAddress)
                            .ethValue(value)
                            .gas(gasLimit)
                            .callData(tx.getData())
                            .worldUpdater(world)
                            .commitWorldState()
                            .tracer(tracer)
                            .execute();

                    success = successArr[0];
                    gasUsed = gasUsedArr[0];
                    output  = outputArr[0];
                } else {
                    // No contract at the target address — treat as out-of-gas abort.
                    success = false;
                    gasUsed = gasLimit;
                }
            }
        }

        // Always increment sender nonce (even on failure) for replay protection.
        long newNonce = currentNonce + 1;
        nonces.put(sender, newNonce);

        // Always deduct fee — no refund on abort (spec: "gas_used is not refunded").
        Wei fee = tx.gasFee(gasUsed);
        world.getAccount(sender).decrementBalance(fee);

        return new EvmResult(success, output, gasUsed, fee);
    }

    // -------------------------------------------------------------------------
    // World-state snapshot / restore  (used for block persistence)
    // -------------------------------------------------------------------------

    /**
     * Returns an immutable snapshot of every account that has ever been created
     * in this world state, keyed by address.
     *
     * <p>The map is ordered by account creation time (insertion order) so that
     * the resulting JSON is deterministic across restarts.
     */
    public Map<Address, Account> snapshotWorldState() {
        Map<Address, Account> snapshot = new LinkedHashMap<>();
        for (Address addr : knownAddresses) {
            Account acc = getAccount(addr);
            if (acc != null) {
                // Usa o nonce atualizado do mapa nonces
                long nonce = nonces.getOrDefault(addr, acc.nonce());
                snapshot.put(addr, new Account(
                    acc.type(),
                    acc.address(),
                    acc.balance(),
                    nonce,
                    acc.code(),
                    acc.storage()
                ));
            }
        }
        return snapshot;
    }

    /**
     * Reconstructs the world state from a persisted block's {@code world_state} map.
     *
     * <p>All existing accounts are replaced; this should only be called on a
     * freshly constructed {@link EvmService}.
     *
     * @param worldState map of {@code "0x..."} address strings to
     *                   {@link PersistedBlock.AccountEntry} values
     */
    public void restoreWorldState(Map<String, PersistedBlock.AccountEntry> worldState) {
        for (Map.Entry<String, PersistedBlock.AccountEntry> entry : worldState.entrySet()) {
            Address address = Address.fromHexString(entry.getKey());
            PersistedBlock.AccountEntry acc = entry.getValue();

            Wei balance = Wei.of(new BigInteger(acc.balance));
            world.createAccount(address, acc.nonce, balance);
            nonces.put(address, acc.nonce);
            knownAddresses.add(address);

            if (acc.code != null && !acc.code.isEmpty()) {
                ((SimpleAccount) world.getAccount(address))
                        .setCode(Bytes.fromHexString(acc.code));
            }

            if (acc.storage != null) {
                var mutableAccount = world.getAccount(address);
                for (Map.Entry<String, String> slot : acc.storage.entrySet()) {
                    mutableAccount.setStorageValue(
                            UInt256.fromHexString(slot.getKey()),
                            UInt256.fromHexString(slot.getValue())
                    );
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Address derivation
    // -------------------------------------------------------------------------

    /**
     * Derives a deterministic 20-byte {@link Address} from an RSA public key.
     *
     * <p>Algorithm: SHA-256 of the DER-encoded public key bytes, then take
     * the last 20 bytes of the 32-byte digest — matching Ethereum's convention
     * of using the low-order bytes of a key hash as the account address,
     * adapted for RSA keys (Ethereum uses keccak256 of the uncompressed
     * ECDSA public key; we use SHA-256 of the DER-encoded RSA public key).
     *
     * @param publicKey the RSA public key to derive an address from
     * @return a deterministic, collision-resistant 20-byte address
     */
    public static Address deriveAddress(PublicKey publicKey) {
        byte[] digest = CryptoUtils.hash(publicKey.getEncoded());
        byte[] addressBytes = Arrays.copyOfRange(digest, 12, 32);
        return Address.wrap(Bytes.wrap(addressBytes));
    }
}
