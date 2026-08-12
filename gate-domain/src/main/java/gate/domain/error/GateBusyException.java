package gate.domain.error;

/**
 * The {@code (project, targetRef)} lock is held by someone else (架构落地执行文档 §9).
 *
 * <p>Fail fast, never spin: on Windows {@code FileLock} is an OS-level mandatory lock released
 * automatically when the owning process dies, so there is no stale-lock problem to wait out and
 * no PID-based reclamation to get wrong.
 */
public final class GateBusyException extends GateException {

    private final String key;
    private final String ownerHint;

    public GateBusyException(String key, String ownerHint) {
        super(GateErrorCode.GATE_ERROR_IO, "gate busy for " + key + (ownerHint == null ? "" : " (" + ownerHint + ")"));
        this.key = key;
        this.ownerHint = ownerHint;
    }

    public String key() {
        return key;
    }

    /** Diagnostic only. Never make a decision from this value. */
    public String ownerHint() {
        return ownerHint;
    }
}
