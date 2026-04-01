package depchain.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.HashMap;
import java.util.Map;

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
}
