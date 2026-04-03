package threshsig;

import java.math.BigInteger;

public class Dealer {

  private int keysize;
  private KeyShare[] shares = null;
  private BigInteger vk = null;
  private GroupKey gk;
  private boolean keyInit;
  private Poly poly;

  public Dealer(final int keysize) {
    if (DEBUG) {
      debug("Testing " + keysize + " bit Keys...");
    }
    this.keysize = keysize;
    keyInit = false;
  }

  public void generateKeys(final int k, final int l) {
    BigInteger pr, qr, p, q, d, e, m, n;
    BigInteger groupSize;
    n = m = pr = qr = null;

    if (DEBUG) {
      debug("Attempting to generate group keypair..");
    }

    p = SafePrimeGen.generateStrongPrime(keysize, ThreshUtil.getRandom());
    q = SafePrimeGen.generateStrongPrime(keysize, ThreshUtil.getRandom());

    pr = (p.subtract(ThreshUtil.ONE)).divide(ThreshUtil.TWO);
    qr = (q.subtract(ThreshUtil.ONE)).divide(ThreshUtil.TWO);

    m = pr.multiply(qr);
    n = p.multiply(q);

    groupSize = BigInteger.valueOf(l);

    if (groupSize.compareTo(ThreshUtil.F4) < 0) {
      e = ThreshUtil.F4;
    } else {
      e = new BigInteger(groupSize.bitLength() + 1, 80, ThreshUtil.getRandom());
    }

    d = e.modInverse(m);

    shares = generateKeyShares(d, m, k, l, n);

    vk = generateVerifiers(n, shares);

    gk = new GroupKey(k, l, keysize, vk, e, n);
    keyInit = true;
  }

  public GroupKey getGroupKey() throws ThresholdSigException {
    checkKeyInit();
    return gk;
  }

  public KeyShare[] getShares() throws ThresholdSigException {
    checkKeyInit();
    return shares;
  }

  private void checkKeyInit() throws ThresholdSigException {
    if (keyInit == false) {
      if (DEBUG) {
        debug("Key pair has not been initialized by generateKeys()");
      }
      throw new ThresholdSigException("Key pair has not been initialized by generateKeys()");
    }
  }

  private KeyShare[] generateKeyShares(final BigInteger d, final BigInteger m, final int k,
      final int l, final BigInteger n) {
    BigInteger[] secrets;
    BigInteger rand;
    int randbits;

    poly = new Poly(d, k, m);
    secrets = new BigInteger[l];
    randbits = n.bitLength() + ThreshUtil.L1 - m.bitLength();

    for (int i = 0; i < l; i++) {
      secrets[i] = poly.eval(i + 1);
      rand = (new BigInteger(randbits, ThreshUtil.getRandom())).multiply(m);
      secrets[i] = secrets[i].add(rand);
    }

    final BigInteger delta = Dealer.factorial(l);

    final KeyShare[] s = new KeyShare[l];
    for (int i = 0; i < l; i++) {
      s[i] = new KeyShare(i + 1, secrets[i], n, delta);
    }

    return s;
  }

  private BigInteger generateVerifiers(final BigInteger n, final KeyShare[] secrets) {
    debug("Generating Verifiers");
    BigInteger rand = null;

    for (final KeyShare element : secrets) {
      while (true) {
        rand = new BigInteger(n.bitLength(), ThreshUtil.getRandom());
        final BigInteger d = rand.gcd(n);
        if (d.compareTo(ThreshUtil.ONE) == 0) {
          break;
        }
        debug("Verifier was not relatively prime");
      }
      rand = rand.multiply(rand).mod(n);

      element.setVerifiers(rand.modPow(element.getSecret(), n), rand);
    }

    return rand;
  }

  private static BigInteger factorial(final int l) {
    BigInteger x = BigInteger.valueOf(1l);
    for (int i = 1; i <= l; i++) {
      x = x.multiply(BigInteger.valueOf(i));
    }
    return x;
  }

  private final static boolean DEBUG = true;

  private static void debug(final String s) {
    System.err.println("Dealer: " + s);
  }

  public static void main(final String[] args) {
    int keysize = 512;
    if (args.length > 0) {
      try {
        keysize = Integer.parseInt(args[0]);
      } catch (final Exception e) {
      }
    }

    final Dealer d = new Dealer(keysize);
    d.generateKeys(3, 5);
  }
}
