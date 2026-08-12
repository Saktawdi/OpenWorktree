package gate.domain.snapshot;

/**
 * One capture-integrity finding (架构落地执行文档 §3.3).
 *
 * @param rule     rule id, {@code R1}–{@code R5}
 * @param severity BLOCKER refuses presubmit; WARNING is shown to the reviewer
 * @param detail   human-readable evidence (never a secret value; paths only)
 */
public record IntegrityViolation(String rule, IntegritySeverity severity, String detail) {

    public IntegrityViolation {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("rule must not be blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null");
        }
    }
}
