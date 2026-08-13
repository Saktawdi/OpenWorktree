package gate.domain.policy;

/**
 * Configured strictness (执行文档 D3: default is "reject only on blocker").
 *
 * @param strictness           how findings map to a verdict
 * @param requireCoverage      assert {@code coveredPaths ⊇ changedPaths}; disabling it is a
 *                             deliberate hole and only exists so the constraint is explicit
 * @param maxDiffBytes         hard ceiling; a larger diff routes to NEEDS_HUMAN
 * @param maxDiffLines         hard ceiling; a larger diff routes to NEEDS_HUMAN
 * @param engineAcceptDegraded allow a degraded engine report to proceed; {@code false} (default)
 *                             keeps the fail-closed behaviour of rejection. This is a data-collection
 *                             escape hatch for prism whose JSON has no "files actually reviewed" field
 *                             (N6 / doc/p2-schema-核对.md §5) — it must stay {@code false} in production.
 */
public record Policy(Strictness strictness, boolean requireCoverage, long maxDiffBytes, long maxDiffLines,
                     boolean engineAcceptDegraded) {

    public Policy {
        if (strictness == null) {
            throw new IllegalArgumentException("strictness must not be null");
        }
        if (maxDiffBytes <= 0 || maxDiffLines <= 0) {
            throw new IllegalArgumentException("diff limits must be positive");
        }
    }

    public static Policy defaults() {
        return new Policy(Strictness.BLOCKER_ONLY, true, 2_000_000L, 20_000L, false);
    }
    public enum Strictness {
        /** 执行文档 D3 ①: only BLOCKER rejects. */
        BLOCKER_ONLY,
        /** 执行文档 D3 ②: BLOCKER or WARNING rejects. */
        BLOCKER_AND_WARNING
    }
}