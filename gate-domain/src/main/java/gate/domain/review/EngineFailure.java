package gate.domain.review;

/**
 * The engine did not produce usable evidence (架构落地执行文档 §5.3).
 *
 * <p>Every failure mode is a value here rather than a thrown exception, so a caller cannot
 * "forget to catch" its way into a pass.
 */
public record EngineFailure(
        EngineDescriptor engine,
        FailureKind kind,
        String detail,
        int exitCode) implements ReviewEvidence {

    public EngineFailure {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null");
        }
    }

    @Override
    public <T> T accept(EvidenceVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public enum FailureKind {
        TIMEOUT,
        CRASH,
        UNPARSEABLE,
        MISSING_FIELD,
        COVERAGE_GAP
    }
}
