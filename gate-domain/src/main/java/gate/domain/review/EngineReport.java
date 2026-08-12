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
 */
public record EngineReport(
        EngineDescriptor engine,
        String treeHash,
        List<Finding> findings,
        Set<String> coveredPaths,
        boolean degraded,
        BlobRef rawOutput,
        int exitCode,
        Duration duration) implements ReviewEvidence {

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
