package gate.domain.snapshot;

import java.util.List;

/**
 * Outcome of the capture-integrity rules R1–R5 (架构落地执行文档 §3.3).
 *
 * <p>Closes the ".gitignore invisibility" class: {@code git add -A} obeys exclusion inputs that
 * the agent controls, so content can be absent from the tree and therefore invisible in the
 * reviewer's diff. R1–R3 are hard blockers on the clone; R4–R5 are warnings surfaced to the
 * reviewer and written to the audit log.
 */
public record CaptureIntegrityReport(List<IntegrityViolation> violations) {

    public CaptureIntegrityReport {
        violations = List.copyOf(violations);
    }

    public static CaptureIntegrityReport clean() {
        return new CaptureIntegrityReport(List.of());
    }

    public List<IntegrityViolation> blockers() {
        return violations.stream().filter(v -> v.severity() == IntegritySeverity.BLOCKER).toList();
    }

    public List<IntegrityViolation> warnings() {
        return violations.stream().filter(v -> v.severity() == IntegritySeverity.WARNING).toList();
    }

    public boolean hasBlockers() {
        return !blockers().isEmpty();
    }
}
