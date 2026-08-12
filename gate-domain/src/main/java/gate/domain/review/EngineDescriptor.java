package gate.domain.review;

/**
 * Identity of the engine that produced a piece of evidence (架构落地执行文档 §5.3, §10.1.1).
 *
 * <p>{@code providerId} / {@code modelName} are the only outward-facing LLM coordinates
 * (ADR-9): {@code base_url} and API keys must never reach this record, the ticket tables, the
 * audit log or commit trailers.
 *
 * <p>In P1 the only engine is the manual verdict adapter, which reports
 * {@code engineId="manual"}, {@code providerId="manual"}.
 *
 * @param argvFingerprint stable fingerprint of the argv actually used, so historical results stay
 *                        comparable when flags drift (N4)
 */
public record EngineDescriptor(
        String engineId,
        String engineVersion,
        String argvFingerprint,
        String providerId,
        String modelName) {

    public EngineDescriptor {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        if (engineVersion == null) {
            throw new IllegalArgumentException("engineVersion must not be null");
        }
        if (argvFingerprint == null) {
            throw new IllegalArgumentException("argvFingerprint must not be null");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (modelName == null) {
            throw new IllegalArgumentException("modelName must not be null");
        }
    }
}
