package threshsig;

import java.math.BigInteger;

public class GroupKey {
 
  private int k, l;
  private BigInteger e;
  private BigInteger n;

  public GroupKey(final int k, final int l, final int keysize, final BigInteger v,
      final BigInteger e, final BigInteger n) {
    this.k = k;
    this.l = l;
    this.e = e;
    this.n = n;
  }

  public int getK() {
    return k;
  }

  public int getL() {
    return l;
  }

  public BigInteger getModulus() {
    return n;
  }

  public BigInteger getExponent() {
    return e;
  }
}
