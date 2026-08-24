package gate.ports.engine;
import gate.ports.session.CostHint;


import gate.domain.review.EngineDescriptor;
import gate.domain.review.ReviewEvidence;
import gate.domain.snapshot.Snapshot;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import java.util.Optional;

/**
 * Review engine port (架构落地执行文档 §5.3).
 *
 * <p><b>Contract: {@link #review} never throws.</b> Its body is the one place a
 * {@code catch (Throwable)} is mandatory, converting timeout / crash / bad JSON / missing field /
 * vanished binary into an {@code EngineFailure} <em>value</em>. This is what makes fail-closed a
 * structural property instead of a habit.
 *
 * <p>The port returns evidence and never a verdict. In P1 the only implementation is the manual
 * verdict adapter; P2 adds prism behind the same contract with zero core changes.
 */
public interface ReviewEngine {

    EngineDescriptor describe();

    ReviewEvidence review(ReviewRequest request);

    /**
     * Extracts cost telemetry from the engine's raw output (P4 bypass data — never affects the
     * verdict or blocks publish, 执行文档 §4 P4).
     *
     * <p>Default returns {@link CostHint#EMPTY} (e.g. the manual review engine has no timing/token
     * data). The prism adapter overrides this to parse {@code timing.totalMs}/{@code llmMs} from its
     * JSON output. This is a <b>default method</b> so adding it changes no existing call site and
     * does not alter the {@link #review} contract.
     *
     * @param evidence the evidence produced by {@link #review} (carries the raw output blob ref)
     * @return the cost hint; {@link Optional#empty()} if the engine cannot extract cost data.
     */
    default Optional<CostHint> extractCost(ReviewEvidence evidence) {
        return Optional.empty();
    }

    /**
     * @param danglingCommit the commit built from the snapshot tree, pinned by a gate ref. Engines
     *                       shell out to live git, so they need a real reachable commit rather than
     *                       a free-floating tree (ADR-6).
     */
    record ReviewRequest(
            RepoRef cloneRepo,
            String ticketNo,
            int reviewRound,
            Snapshot snapshot,
            ObjectId danglingCommit) {
    }
}
