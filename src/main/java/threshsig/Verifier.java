package threshsig;

import java.math.BigInteger;
import java.util.Objects;

final class Verifier {
    private final BigInteger z;
    private final BigInteger c;
    private final BigInteger shareVerifier;
    private final BigInteger groupVerifier;

    Verifier(
        BigInteger z,
        BigInteger c,
        BigInteger shareVerifier,
        BigInteger groupVerifier
    ) {
        this.z = Objects.requireNonNull(z, "z");
        this.c = Objects.requireNonNull(c, "c");
        this.shareVerifier = Objects.requireNonNull(shareVerifier, "shareVerifier");
        this.groupVerifier = Objects.requireNonNull(groupVerifier, "groupVerifier");
    }

    BigInteger getZ() {
        return z;
    }

    BigInteger getC() {
        return c;
    }

    BigInteger getShareVerifier() {
        return shareVerifier;
    }

    BigInteger getGroupVerifier() {
        return groupVerifier;
    }
}
