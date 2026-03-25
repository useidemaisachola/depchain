package threshsig;

import java.math.BigInteger;

final class Poly {
    private final BigInteger[] coefficients;

    Poly(BigInteger constantTerm, int degree, BigInteger modulus) {
        if (degree < 0) {
            throw new IllegalArgumentException("degree must be >= 0");
        }
        coefficients = new BigInteger[degree + 1];
        coefficients[0] = constantTerm;
        for (int i = 1; i < coefficients.length; i++) {
            coefficients[i] =
                new BigInteger(modulus.bitLength(), ThreshUtil.getRandom()).mod(modulus);
        }
    }

    BigInteger eval(int x) {
        BigInteger value = coefficients[coefficients.length - 1];
        BigInteger bx = BigInteger.valueOf(x);
        for (int i = coefficients.length - 2; i >= 0; i--) {
            value = value.multiply(bx).add(coefficients[i]);
        }
        return value;
    }
}
