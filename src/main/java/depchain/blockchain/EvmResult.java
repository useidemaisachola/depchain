package depchain.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Optional;

/**
 * Outcome of a transaction executed via {@link EvmService#executeTransaction(Transaction)}.
 *
 * @param success  {@code true} if execution completed without revert or exceptional halt
 * @param output   ABI-encoded return data (calls), empty for transfers;
 *                 for deployments, contains the 20-byte deployed contract address
 * @param gasUsed  actual gas consumed by the EVM (≤ gasLimit)
 * @param fee      DepCoin fee deducted from the sender: {@code gasPrice × gasUsed}
 */

public record EvmResult(boolean success, Bytes output, long gasUsed, Wei fee) {

    public Optional<Address> deployedAddress() {
        if (output == null || output.size() != 20) return Optional.empty();
        return Optional.of(Address.wrap(output));
    }
}
