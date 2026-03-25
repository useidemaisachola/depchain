package threshsig;

public class ThresholdSigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ThresholdSigException(String message) {
        super(message);
    }

    public ThresholdSigException(String message, Throwable cause) {
        super(message, cause);
    }
}
