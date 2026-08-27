package gate.domain.review;

import gate.domain.blob.BlobRef;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The engine produced parseable output (架构落地执行文档 §5.3).
 *
 * <p>A report is <em>evidence, not a verdict</em>: even an empty finding list is not "pass".
 * Only {@code GatePolicy} turns evidence into a decision.
 *
 * @param treeHash     proves which snapshot this report describes
 * @param coveredPaths the files the engine <em>actually</em> reviewed; the policy asserts
 *                     {@code coveredPaths ⊇ changedPaths}, otherwise an engine that silently
 *                     skips binary/oversized/failed chunks would let unreviewed content through
 *                     with no exception anywhere
 * @param degraded     the adapter could not fully normalise something; forces reject
 * @param promptTokens LLM prompt tokens when the engine exposed usage; null otherwise
 * @param completionTokens LLM completion tokens when exposed; null otherwise
 * @param totalTokens  LLM total tokens when exposed; null otherwise
 */
public record EngineReport(
        EngineDescriptor engine,
        String treeHash,
        List<Finding> findings,
        Set<String> coveredPaths,
        boolean degraded,
        BlobRef rawOutput,
        int exitCode,
        Duration duration,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens) implements ReviewEvidence {

    /**
     * 不带 token 遥测的兼容构造器：供无 usage 来源的证据使用（人工判定等），
     * token 字段落 null，成本遥测按 tokenSource=unavailable 记录。
     */
    public EngineReport(EngineDescriptor engine, String treeHash, List<Finding> findings,
                        Set<String> coveredPaths, boolean degraded, BlobRef rawOutput,
                        int exitCode, Duration duration) {
        this(engine, treeHash, findings, coveredPaths, degraded, rawOutput, exitCode, duration,
                null, null, null);
    }

    public EngineReport {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        if (treeHash == null || treeHash.isBlank()) {
            throw new IllegalArgumentException("treeHash must not be blank");
        }
        if (duration == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
        findings = List.copyOf(findings);
        coveredPaths = Set.copyOf(coveredPaths);
    }

    @Override
    public <T> T accept(EvidenceVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
