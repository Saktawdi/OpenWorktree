package gate.domain.error;

/**
 * Machine-consumable failure classes, mapped 1:1 onto the exit-code table (架构落地执行文档 §8.3).
 *
 * <p>The numeric code lives in the domain only as data; the single {@code System.exit} call site
 * is in the CLI adapter. {@code 0} must mean exactly "you may proceed": a reject is frequent but
 * is not success, and it must stay distinguishable from a gate malfunction so an orchestrator can
 * retry malfunctions while feeding findings back on rejects.
 */
public enum GateErrorCode {

    OK(0, "PASS / allowed"),
    REJECT_FINDINGS(10, "REJECT — review findings (blocker)"),
    REJECT_TOCTOU(11, "REJECT — TOCTOU / tree mismatch"),
    REJECT_PRECONDITION(12, "REJECT — precondition (base moved / non-FF / empty diff / ref not whitelisted / merge in progress)"),
    REJECT_NEEDS_HUMAN(13, "REJECT — needs human (binary / oversized / coverage gap)"),
    GATE_ERROR_ENGINE(20, "GATE_ERROR — engine failure"),
    GATE_ERROR_IO(21, "GATE_ERROR — git / IO / lock busy / DB"),
    GATE_ERROR_CONFIG(22, "GATE_ERROR — configuration (hook drift / advertisePushOptions off / engine missing)"),
    USAGE(64, "USAGE"),
    INTERNAL(70, "internal, unreachable");

    private final int code;
    private final String description;

    GateErrorCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }
}
