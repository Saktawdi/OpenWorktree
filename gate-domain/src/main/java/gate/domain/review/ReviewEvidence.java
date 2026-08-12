package gate.domain.review;

/**
 * Review evidence: either a report or a failure. Nothing else is representable.
 *
 * <p>fail-closed is guaranteed by the type structure (架构落地执行文档 §8.2 / ADR-7): the
 * {@link ReviewEngine} port declares it never throws, so timeouts, crashes, bad JSON, missing
 * fields and a vanished binary all become <em>values</em> of this sealed hierarchy.
 *
 * <p>The consumer entry point is the {@code accept} abstract method rather than a
 * {@code switch} pattern: in Java 17 switch patterns are still preview, so exhaustiveness is not
 * compiler-enforced. With a visitor on a sealed interface, adding a third subtype makes every
 * consumer fail to compile until it handles the new case.
 */
public sealed interface ReviewEvidence permits EngineReport, EngineFailure {

    <T> T accept(EvidenceVisitor<T> visitor);
}
