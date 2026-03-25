package threshsig;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class KeyShare {
    private final int id;
    private final BigInteger secret;
    private final BigInteger modulus;
    private final BigInteger delta;
    private final BigInteger signValue;

    private BigInteger verifier;
    private BigInteger groupVerifier;

    public KeyShare(int id, BigInteger secret, BigInteger modulus, BigInteger delta) {
        this.id = id;
        this.secret = Objects.requireNonNull(secret, "secret");
        this.modulus = Objects.requireNonNull(modulus, "modulus");
        this.delta = Objects.requireNonNull(delta, "delta");
        this.signValue = ThreshUtil.FOUR.multiply(delta).multiply(secret);
    }

    public int getId() {
        return id;
    }

    public BigInteger getSecret() {
        return secret;
    }

    public BigInteger getVerifier() {
        return verifier;
    }

    public BigInteger getGroupVerifier() {
        return groupVerifier;
    }

    public void setVerifiers(BigInteger verifier, BigInteger groupVerifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.groupVerifier = Objects.requireNonNull(groupVerifier, "groupVerifier");
    }

    public SigShare sign(byte[] data) {
        if (verifier == null || groupVerifier == null) {
            throw new ThresholdSigException("Share verifiers were not initialized");
        }

        BigInteger x = new BigInteger(data).mod(modulus);
        int randomBits = modulus.bitLength() + 3 * ThreshUtil.L1;
        BigInteger r = new BigInteger(randomBits, ThreshUtil.getRandom());
        BigInteger vPrime = groupVerifier.modPow(r, modulus);
        BigInteger xTilde =
            x.modPow(ThreshUtil.FOUR.multiply(delta), modulus);
        BigInteger xPrime = xTilde.modPow(r, modulus);

        MessageDigest digest = getSha1();
        digest.update(groupVerifier.mod(modulus).toByteArray());
        digest.update(xTilde.toByteArray());
        digest.update(verifier.mod(modulus).toByteArray());
        digest.update(x.modPow(signValue, modulus).modPow(ThreshUtil.TWO, modulus).toByteArray());
        digest.update(vPrime.toByteArray());
        digest.update(xPrime.toByteArray());

        BigInteger c = new BigInteger(digest.digest()).mod(modulus);
        BigInteger z = c.multiply(secret).add(r);
        Verifier shareVerifier = new Verifier(z, c, verifier, groupVerifier);
        return new SigShare(id, x.modPow(signValue, modulus), shareVerifier);
    }

    private static MessageDigest getSha1() {
        try {
            return MessageDigest.getInstance("SHA");
        } catch (NoSuchAlgorithmException e) {
            throw new ThresholdSigException("SHA digest is not available", e);
        }
    }
}
