package gate.domain.publish;

/** Lifecycle of a {@code publish_intent} row (架构落地执行文档 §7.2). */
public enum PublishStatus {
    /** Written ahead of every irreversible step. */
    PENDING,
    /** {@code published(intent)} evaluated true against auth.git. */
    PUBLISHED,
    /** Base moved / snapshot invalidated; a new presubmit round is required. */
    ABANDONED
}
