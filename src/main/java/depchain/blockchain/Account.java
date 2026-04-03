package depchain.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record Account(
        AccountType type,
        Address address,
        Wei balance,
        long nonce,
        Bytes code,
        Map<UInt256, UInt256> storage
) {
    public Account {
        Objects.requireNonNull(type,    "type must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(balance, "balance must not be null");
        Objects.requireNonNull(code,    "code must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
        storage = Collections.unmodifiableMap(storage);
    }

    public boolean isEoa()      { return type == AccountType.EOA; }
    public boolean isContract() { return type == AccountType.CONTRACT; }
}
