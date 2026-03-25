package threshsig;

import java.math.BigInteger;
import java.util.Objects;

public final class SigShare {
    private final int id;
    private final BigInteger signature;
    private final Verifier verifier;

    public SigShare(int id, BigInteger signature, Verifier verifier) {
        this.id = id;
        this.signature = Objects.requireNonNull(signature, "signature");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public int getId() {
        return id;
    }

    public BigInteger getSignature() {
        return signature;
    }

    Verifier getVerifier() {
        return verifier;
    }
}
