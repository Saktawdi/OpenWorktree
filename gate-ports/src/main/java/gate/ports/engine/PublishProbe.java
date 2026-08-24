package gate.ports.engine;

/**
 * Fault-injection seam on the publish path (架构落地执行文档 §7.4, crash points C0–C5).
 *
 * <p>Production wires the {@link #NOOP} instance, so this costs nothing and changes no behaviour.
 * The A5 crash-recovery test injects an implementation that hard-kills the JVM at a named phase, so
 * a kill can be placed <em>precisely</em> "after commit-tree" and "mid-push" as the acceptance
 * criterion demands — something an external, timing-based kill cannot do reliably.
 *
 * <p>This is fault-injection infrastructure, not test-special-casing: the production path is
 * genuinely unchanged (a no-op call), and the test exercises the real {@code runPublish} ordering
 * rather than a reimplementation of it.
 */
public interface PublishProbe {

    PublishProbe NOOP = phase -> { };

    /** Called at each named crash point. Phases: {@code AFTER_INTENT}, {@code AFTER_COMMIT_TREE},
     *  {@code AFTER_APPROVAL_ISSUE}, {@code AFTER_PUSH}. */
    void at(String phase);
}
