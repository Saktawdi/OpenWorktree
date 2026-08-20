package gate.ports;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Isolation and lifecycle for worker ephemeral workspaces (production-architecture §4.2, §11).
 * Local: clone per ticket under clonesRoot; enterprise: container-isolated.
 */
public interface EphemeralWorkspaceManager {

    /** Allocate a fresh workspace for a task (reconstructable from persisted snapshot). */
    Path allocate(String taskId, String ticketNo, String baseCommitOid, String targetRef);

    /** Reconstruct workspace from snapshot after worker loss (idempotent). */
    Path reconstruct(String ticketNo, String baseCommitOid, String targetRef);

    /** Release and GC workspace after task terminal. Returns true if cleaned. */
    boolean release(String taskId, Path workspace);

    /** GC orphaned workspaces older than threshold. Returns cleaned count. */
    int gcOrphans(java.time.Instant threshold);

    /** Returns true if workspace is isolated (no host docker socket, no cross-tenant). */
    boolean isIsolated(Path workspace);

    /** Find workspace by ticket. */
    Optional<Path> findWorkspace(String ticketNo);
}
