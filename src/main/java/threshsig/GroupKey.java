package threshsig;

import java.math.BigInteger;
import java.util.Objects;

public final class GroupKey {
    private final int k;
    private final int l;
    private final BigInteger exponent;
    private final BigInteger modulus;

    public GroupKey(int k, int l, BigInteger exponent, BigInteger modulus) {
        this.k = k;
        this.l = l;
        this.exponent = Objects.requireNonNull(exponent, "exponent");
        this.modulus = Objects.requireNonNull(modulus, "modulus");
    }

    public int getK() {
        return k;
    }

    public int getL() {
        return l;
    }

    public BigInteger getExponent() {
        return exponent;
    }

    public BigInteger getModulus() {
        return modulus;
    }
}
