package threshsig;

import java.math.BigInteger;

class Poly {
  private BigInteger[] coeff;

  private int size;

  public Poly(final BigInteger d, final int size, final BigInteger m) {
    final int bitLength = m.bitLength();

    this.size = size;
    coeff = new BigInteger[size];
    coeff[0] = d;
    for (int i = 1; i < size; i++) {
      coeff[i] = (new BigInteger(bitLength, ThreshUtil.getRandom())).mod(m);
    }
  }

  public BigInteger eval(final BigInteger x) {
    BigInteger retVal = coeff[size - 1];

    for (int i = size - 2; i >= 0; i--) {
      retVal = (retVal.multiply(x)).add(coeff[i]);
    }
    return retVal;
  }

  public BigInteger eval(final int x) {
    final BigInteger bx = BigInteger.valueOf(x);
    return this.eval(bx);
  }

  @Override
  public String toString() {
    String out = "[";
    for (int i = 0; i < size; i++) {
      out += coeff[i].toString() + " ";
    }
    out += "]";
    return out;
  }
}
