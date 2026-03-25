package threshsig;

import java.math.BigInteger;
import java.security.SecureRandom;

final class ThreshUtil {
    static final BigInteger ZERO = BigInteger.ZERO;
    static final BigInteger ONE = BigInteger.ONE;
    static final BigInteger TWO = BigInteger.valueOf(2L);
    static final BigInteger FOUR = BigInteger.valueOf(4L);
    static final BigInteger F4 = BigInteger.valueOf(0x10001L);
    static final int L1 = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ThreshUtil() {}

    static SecureRandom getRandom() {
        return RANDOM;
    }
}
