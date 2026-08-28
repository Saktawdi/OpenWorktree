package gate.ports.git;

/**
 * Ticket-level base sync (T-118): resolves the ticket's clone, authoritative repo and base ref,
 * then delegates to {@link CloneBaseSyncer}. This is the narrow interface driver adapters consume —
 * the application-layer implementation adds the stage guards (review-gated stages have their base
 * frozen while a round is open — TOCTOU) and the audit trail.
 */
public interface BaseSynchronizer {

    /** @see CloneBaseSyncer#sync for the returned report semantics */
    CloneBaseSyncer.Report syncBase(String ticketNo, boolean allowDirty);
}
