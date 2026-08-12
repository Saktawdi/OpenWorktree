package gate.ports;

import gate.domain.review.EngineDescriptor;
import gate.domain.review.ReviewEvidence;
import gate.domain.snapshot.Snapshot;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;

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
