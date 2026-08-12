package gate.ports;

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
     * @param pass the human verdict for this round
     * @param note reject reason; becomes the BLOCKER finding message when {@code pass} is false
     */
    ReviewEngine forManualVerdict(boolean pass, String note);

    /**
     * @return the live prism engine, or {@code null} when no engine is configured (P1 / no prism wired).
     *         Callers must check {@link gate.domain.config.GateConfig#engineConfigured()} first.
     */
    ReviewEngine forPrism();
}
