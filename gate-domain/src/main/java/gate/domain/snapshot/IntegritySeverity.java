package gate.domain.snapshot;

/** Whether an integrity finding blocks presubmit or only annotates it (架构落地执行文档 §3.3 R1–R5). */
public enum IntegritySeverity {
    /** R1–R3: cheap preflight on the clone. Presubmit is refused. */
    BLOCKER,
    /** R4–R5: reviewer-visible annotation, recorded in the audit log. */
    WARNING
}
