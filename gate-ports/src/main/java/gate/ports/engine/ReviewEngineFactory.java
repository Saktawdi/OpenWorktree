package gate.ports.engine;

/**
 * Supplies the {@link ReviewEngine} to use for a review round.
 *
 * <p>The application layer must not depend on any concrete adapter (§4.2), yet it needs an engine to
 * gather evidence. This factory is the seam: in P1 the adapter returns a manual-verdict engine built
 * from the human decision; in P2 it can also return the prism adapter. The application selects which by
 * asking for {@code manual} or {@code prism} and never names a concrete class.
 */
public interface ReviewEngineFactory {

    /**
     * @param pass the human verdict for this round: {@code true} pass, {@code false} reject,
     *             {@code null} nobody has decided yet — with no engine configured the adapter emits
     *             "undecided" evidence whose empty coverage the policy routes to REQUIRES_HUMAN
     *             (Fail-Closed, 架构规范 I7) instead of guessing
     * @param note reject reason; becomes the BLOCKER finding message when {@code pass} is false
     */
    ReviewEngine forManualVerdict(Boolean pass, String note);

    /**
     * @return the configured built-in engine ({@code engine.kind="gate-engine"}), or {@code null}
     *         when no engine is configured. Callers must check
     *         {@link gate.domain.config.GateConfig#engineConfigured()} first.
     */
    ReviewEngine builtin();
}
