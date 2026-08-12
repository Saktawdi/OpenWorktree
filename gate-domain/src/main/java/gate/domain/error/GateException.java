package gate.domain.error;

/**
 * Carries a {@link GateErrorCode} to the single exit point in the CLI adapter.
 *
 * <p>Deliberately unchecked: the review path must never be able to swallow one of these into a
 * pass, and 执行文档 §8.3 forbids swallowing exceptions on the review path at all.
 */
public class GateException extends RuntimeException {

    private final GateErrorCode code;

    public GateException(GateErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public GateException(GateErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public GateErrorCode code() {
        return code;
    }
}
