package gate.adapters.workspace;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.EphemeralWorkspaceManager;
import gate.ports.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filesystem ephemeral workspace manager (Phase 3 §4.2, §11).
 * Each task gets an isolated ephemeral clone under clonesRoot/ephemeral/<taskId>.
 * Reconstructable from persisted snapshot (ticket.clonePath + baseCommit + targetRef).
 * GC removes orphans without DB task reference.
 */
public final class FsEphemeralWorkspaceManager implements EphemeralWorkspaceManager {

    private final Path clonesRoot;
    private final Path authRepoPath;
    private final gate.adapters.git.GitCli git;
    private final ConcurrentHashMap<String, Path> active = new ConcurrentHashMap<>();

    public FsEphemeralWorkspaceManager(Path clonesRoot, Path authRepoPath, gate.adapters.git.GitCli git) {
        this.clonesRoot = clonesRoot.toAbsolutePath().normalize();
        this.authRepoPath = authRepoPath.toAbsolutePath().normalize();
        this.git = git;
        try { Files.createDirectories(clonesRoot.resolve("ephemeral")); } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create ephemeral root", e);
        }
    }

    @Override
    public Path allocate(String taskId, String ticketNo, String baseCommitOid, String targetRef) {
        Path ws = clonesRoot.resolve("ephemeral").resolve(sanitize(taskId));
        if (Files.exists(ws)) {
            return ws;
        }
        try {
            Files.createDirectories(ws.getParent());
            // clone from auth repo, then checkout baseCommit
            ProcessRunner.ProcRun clone = git.run(null, java.util.Map.of(), "clone", authRepoPath.toString(), ws.toString());
            if (!clone.ok()) throw new GateException(GateErrorCode.GATE_ERROR_IO, "clone failed: " + clone.stderrFirstLine());
            if (baseCommitOid != null && !baseCommitOid.isBlank()) {
                gate.ports.ProcessRunner.ProcRun checkout = git.run(ws, java.util.Map.of(), "checkout", "--detach", baseCommitOid);
                if (!checkout.ok()) throw new GateException(GateErrorCode.GATE_ERROR_IO, "checkout failed: " + checkout.stderrFirstLine());
            }
            active.put(taskId, ws);
            return ws;
        } catch (GateException e) { throw e; }
        catch (Exception e) { throw new GateException(GateErrorCode.GATE_ERROR_IO, "allocate failed", e); }
    }

    @Override
    public Path reconstruct(String ticketNo, String baseCommitOid, String targetRef) {
        // Reconstruct is same as allocate with deterministic id
        String syntheticId = "recon-" + ticketNo + "-" + (baseCommitOid == null ? "head" : baseCommitOid.substring(0,7));
        return allocate(syntheticId, ticketNo, baseCommitOid, targetRef);
    }

    @Override
    public boolean release(String taskId, Path workspace) {
        active.remove(taskId);
        if (workspace == null) return false;
        try {
            // security: ensure inside clonesRoot/ephemeral
            Path norm = workspace.toAbsolutePath().normalize();
            if (!norm.startsWith(clonesRoot)) return false;
            if (!Files.exists(norm)) return true;
            // check isolation: no docker socket, no cross-tenant
            if (!isIsolated(norm)) return false;
            deleteRecursive(norm);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override
    public int gcOrphans(Instant threshold) {
        Path eph = clonesRoot.resolve("ephemeral");
        if (!Files.isDirectory(eph)) return 0;
        int[] count = {0};
        try (var stream = Files.list(eph)) {
            stream.forEach(p -> {
                try {
                    var attrs = Files.getLastModifiedTime(p);
                    if (attrs.toInstant().isBefore(threshold)) {
                        // only GC if not in active map
                        boolean isActive = active.containsValue(p.toAbsolutePath().normalize());
                        if (!isActive) {
                            deleteRecursive(p);
                            count[0]++;
                        }
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return count[0];
    }

    @Override public boolean isIsolated(Path workspace) {
        if (workspace == null) return false;
        Path norm = workspace.toAbsolutePath().normalize();
        // must be under clonesRoot/ephemeral and not contain symlink escape
        if (!norm.startsWith(clonesRoot.resolve("ephemeral"))) return false;
        // check no access to host docker socket / controls
        if (Files.exists(norm.resolve("docker.sock"))) return false;
        // no traversal to other tenant (enforced by path)
        return true;
    }

    @Override public Optional<Path> findWorkspace(String ticketNo) {
        // best effort: find active containing ticketNo
        return active.entrySet().stream().filter(e -> e.getKey().contains(ticketNo)).map(e -> e.getValue()).findFirst();
    }

    private static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static void deleteRecursive(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a,b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }
}
