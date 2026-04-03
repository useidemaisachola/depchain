package threshsig;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Random;

public class KeyShare {

  private BigInteger secret;
  /** Verifier used to authenticate self to other shares */
  private BigInteger verifier;
  private BigInteger groupVerifier;

  private BigInteger n;
  private final BigInteger delta;

  /** The secret key value used to sign messages.*/
  private BigInteger signVal;
  private int id;

  private static SecureRandom random;
  private MessageDigest md;
  static {
    final byte[] randSeed = new byte[20];
    (new Random()).nextBytes(randSeed);
    random = new SecureRandom(randSeed);
  }

  public KeyShare(final int id, final BigInteger secret, final BigInteger n, final BigInteger delta) {
    this.id = id;
    this.secret = secret;

    verifier = null;

    this.n = n;
    this.delta = delta;
    signVal = ThreshUtil.FOUR.multiply(delta).multiply(secret);
  }

 
  public int getId() {
    return id;
  }

  public BigInteger getSecret() {
    return secret;
  }

  public void setVerifiers(final BigInteger verifier, final BigInteger groupVerifier) {
    this.verifier = verifier;
    this.groupVerifier = groupVerifier;
  }

  public BigInteger getVerifier() {
    return verifier;
  }

  public BigInteger getGroupVerifier() {
    return groupVerifier;
  }

  public BigInteger getSignVal() {
    return signVal;
  }

  @Override
  public String toString() {
    return "KeyShare[" + id + "]";
  }

 
  public SigShare sign(final byte[] b) {
    final BigInteger x = (new BigInteger(b)).mod(n);

    final int randbits = n.bitLength() + 3 * ThreshUtil.L1;

    final BigInteger r = (new BigInteger(randbits, random));
    final BigInteger vprime = groupVerifier.modPow(r, n);
    final BigInteger xtilde = x.modPow(ThreshUtil.FOUR.multiply(delta), n);
    final BigInteger xprime = xtilde.modPow(r, n);

    BigInteger c = null;
    BigInteger z = null;
    try {
      md = MessageDigest.getInstance("SHA");
      md.reset();

      md.update(groupVerifier.mod(n).toByteArray());
      md.update(xtilde.toByteArray());
      md.update(verifier.mod(n).toByteArray());
      md.update(x.modPow(signVal, n).modPow(ThreshUtil.TWO, n).toByteArray());
      md.update(vprime.toByteArray());
      md.update(xprime.toByteArray());
      c = new BigInteger(md.digest()).mod(n);
      z = (c.multiply(secret)).add(r);
    } catch (final java.security.NoSuchAlgorithmException e) {
      debug("Provider could not locate SHA message digest .");
      e.printStackTrace();
    }

    final Verifier ver = new Verifier(z, c, verifier, groupVerifier);

    return new SigShare(id, x.modPow(signVal, n), ver);
  }

  private static void debug(final String s) {
    System.err.println("KeyShare: " + s);
  }
}
