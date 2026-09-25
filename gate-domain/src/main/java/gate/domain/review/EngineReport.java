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
 * @param skippedPaths changed paths the engine deliberately did not review, each with its reason
 *                     (binary / secret path / project rule / deleted / per-file token gate). The
 *                     policy subtracts the authorised reasons from the coverage denominator and
 *                     routes {@code too_large} to REQUIRES_HUMAN — see {@link SkippedPath}.
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
        List<SkippedPath> skippedPaths,
        boolean degraded,
        BlobRef rawOutput,
        int exitCode,
        Duration duration,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        List<Finding> filteredFindings) implements ReviewEvidence {

    /** Back-compatible constructor for engines that neither skip paths nor run a filter pass. */
    public EngineReport(EngineDescriptor engine, String treeHash, List<Finding> findings,
                        Set<String> coveredPaths, boolean degraded, BlobRef rawOutput,
                        int exitCode, Duration duration) {
        this(engine, treeHash, findings, coveredPaths, List.of(), degraded, rawOutput, exitCode,
                duration, null, null, null, List.of());
    }

    /** Back-compatible constructor carrying token telemetry but no skip/filter detail. */
    public EngineReport(EngineDescriptor engine, String treeHash, List<Finding> findings,
                        Set<String> coveredPaths, boolean degraded, BlobRef rawOutput,
                        int exitCode, Duration duration,
                        Long promptTokens, Long completionTokens, Long totalTokens) {
        this(engine, treeHash, findings, coveredPaths, List.of(), degraded, rawOutput, exitCode,
                duration, promptTokens, completionTokens, totalTokens, List.of());
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
        skippedPaths = List.copyOf(skippedPaths);
        filteredFindings = List.copyOf(filteredFindings);
    }

    @Override
    public <T> T accept(EvidenceVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
