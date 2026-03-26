package threshsig;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

class SafePrimeGen {

  private static final Logger log = Logger.getLogger(SafePrimeGen.class.getName());

  private static final int certainty = 101;

  public static BigInteger generateStrongPrime(final int bitLength,
      final SecureRandom random) {
    final int i0BitLengthTargetValue = 12;
    final int j0BitLengthTargetValue = 12;
    final int tBitLength = bitLength / 2 - i0BitLengthTargetValue;

    final BigInteger t = new BigInteger(tBitLength, certainty, random);

    final int i0BitLength = bitLength / 2 - t.bitLength();
    final BigInteger i0 = new BigInteger("0").setBit(i0BitLength - 1);

    BigInteger a;
    BigInteger b;
    BigInteger c;
    BigInteger d;

    a = t.multiply(ThreshUtil.TWO);

    BigInteger r;
    BigInteger i = i0;

    do {
      b = a.multiply(i);
      r = b.add(ThreshUtil.ONE);
      i = i.add(ThreshUtil.ONE);
    } while (r.isProbablePrime(certainty) == false);

    BigInteger p = null;

    outerloop: do {
      final int sBitLength = bitLength - r.bitLength() - j0BitLengthTargetValue;
      final BigInteger s = new BigInteger(sBitLength, certainty, random);

      BigInteger p0;

      a = s.multiply(ThreshUtil.TWO);
      b = r.subtract(ThreshUtil.TWO);
      c = s.modPow(b, r);
      d = c.multiply(a);
      p0 = d.subtract(ThreshUtil.ONE);

      a = ThreshUtil.TWO.multiply(r).multiply(s);

      b = BigInteger.valueOf(0L).setBit(bitLength - 1).subtract(p0);
      c = ThreshUtil.TWO.multiply(r).multiply(s);
      BigInteger j0 = b.divide(c);

      if (b.mod(c).equals(ThreshUtil.ZERO) == false) {
        j0 = j0.add(BigInteger.ONE);
      }

      BigInteger j = j0;

      do {
        p = a.multiply(j).add(p0);
        j = j.add(BigInteger.ONE);
        if (p.bitLength() > bitLength) {
          continue outerloop;
        }
      } while (p.isProbablePrime(certainty) == false);

    } while (p.bitLength() != bitLength);

    return p;
  }
}
