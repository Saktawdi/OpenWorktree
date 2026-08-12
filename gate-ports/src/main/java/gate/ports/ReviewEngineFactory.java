package gate.ports;

/**
 * Supplies the {@link ReviewEngine} to use for a review round.
 *
 * <p>The application layer must not depend on any concrete adapter (§4.2), yet it needs an engine to
 * gather evidence. This factory is the seam: in P1 the adapter returns a manual-verdict engine built
 * from the human decision; in P2 it returns the prism adapter. The application calls
 * {@link #forManualVerdict} and never names a concrete class.
 */
public interface ReviewEngineFactory {

    /**
     * @param pass the human verdict for this round
     * @param note reject reason; becomes the BLOCKER finding message when {@code pass} is false
     */
    ReviewEngine forManualVerdict(boolean pass, String note);
}
