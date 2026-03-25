package threshsig;

import java.math.BigInteger;

public final class Dealer {
    private final int keySizeBits;

    private GroupKey groupKey;
    private KeyShare[] shares;

    public Dealer(int keySizeBits) {
        if (keySizeBits < 256) {
            throw new IllegalArgumentException("keySizeBits must be >= 256");
        }
        this.keySizeBits = keySizeBits;
    }

    public void generateKeys(int threshold, int participants) {
        if (threshold < 1 || threshold > participants) {
            throw new IllegalArgumentException("Invalid threshold parameters");
        }

        BigInteger p = generateSafePrime(keySizeBits);
        BigInteger q;
        do {
            q = generateSafePrime(keySizeBits);
        } while (p.equals(q));

        BigInteger pPrime = p.subtract(ThreshUtil.ONE).divide(ThreshUtil.TWO);
        BigInteger qPrime = q.subtract(ThreshUtil.ONE).divide(ThreshUtil.TWO);
        BigInteger modulus = p.multiply(q);
        BigInteger m = pPrime.multiply(qPrime);

        BigInteger exponent = choosePublicExponent(participants, m);
        BigInteger secretExponent = exponent.modInverse(m);

        Poly polynomial = new Poly(secretExponent, threshold - 1, m);
        BigInteger delta = ThresholdSignatures.factorial(participants);
        int randomBits = Math.max(64, modulus.bitLength() + ThreshUtil.L1 - m.bitLength());

        shares = new KeyShare[participants];
        for (int i = 0; i < participants; i++) {
            BigInteger secret = polynomial.eval(i + 1);
            BigInteger blinding =
                new BigInteger(randomBits, ThreshUtil.getRandom()).multiply(m);
            KeyShare share = new KeyShare(i + 1, secret.add(blinding), modulus, delta);
            shares[i] = share;
        }

        BigInteger groupVerifier = pickQuadraticResidue(modulus);
        for (KeyShare share : shares) {
            share.setVerifiers(
                groupVerifier.modPow(share.getSecret(), modulus),
                groupVerifier
            );
        }

        groupKey = new GroupKey(threshold, participants, exponent, modulus);
    }

    public GroupKey getGroupKey() {
        if (groupKey == null) {
            throw new ThresholdSigException("Group key has not been generated yet");
        }
        return groupKey;
    }

    public KeyShare[] getShares() {
        if (shares == null) {
            throw new ThresholdSigException("Threshold shares have not been generated yet");
        }
        return shares.clone();
    }

    private static BigInteger choosePublicExponent(int participants, BigInteger m) {
        if (
            ThreshUtil.F4.compareTo(BigInteger.valueOf(participants)) > 0 &&
            ThreshUtil.F4.gcd(m).equals(ThreshUtil.ONE)
        ) {
            return ThreshUtil.F4;
        }

        BigInteger exponent;
        do {
            exponent = BigInteger.probablePrime(
                BigInteger.valueOf(participants).bitLength() + 2,
                ThreshUtil.getRandom()
            );
        } while (
            exponent.compareTo(BigInteger.valueOf(participants)) <= 0 ||
            !exponent.gcd(m).equals(ThreshUtil.ONE)
        );
        return exponent;
    }

    private static BigInteger generateSafePrime(int bits) {
        while (true) {
            BigInteger q = BigInteger.probablePrime(bits - 1, ThreshUtil.getRandom());
            BigInteger p = q.shiftLeft(1).add(ThreshUtil.ONE);
            if (p.isProbablePrime(120)) {
                return p;
            }
        }
    }

    private static BigInteger pickQuadraticResidue(BigInteger modulus) {
        while (true) {
            BigInteger candidate =
                new BigInteger(modulus.bitLength(), ThreshUtil.getRandom()).mod(modulus);
            if (candidate.signum() == 0) {
                continue;
            }
            if (!candidate.gcd(modulus).equals(ThreshUtil.ONE)) {
                continue;
            }
            return candidate.multiply(candidate).mod(modulus);
        }
    }
}
