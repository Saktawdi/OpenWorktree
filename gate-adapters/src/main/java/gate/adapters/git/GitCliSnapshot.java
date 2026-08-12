package gate.adapters.git;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.snapshot.CaptureIntegrityReport;
import gate.domain.snapshot.IntegritySeverity;
import gate.domain.snapshot.IntegrityViolation;
import gate.domain.snapshot.Snapshot;
import gate.ports.ProcessRunner;
import gate.ports.SnapshotCapture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only {@link SnapshotCapture} implementation: freezes a clone's worktree into an immutable tree
 * with the real git binary (架构落地执行文档 §3.2/§3.3, ADR-1/ADR-5).
 *
 * <p>Capture uses a <b>brand-new empty temporary index</b> pointed at by {@code GIT_INDEX_FILE},
 * and deliberately does <em>not</em> {@code read-tree HEAD} first:
 * <ul>
 *   <li>the agent's real index stays byte-identical across capture (measured in P0; asserted by
 *       {@code CaptureDoesNotPolluteAgentIndexTest}), so the gate never disturbs the agent's work;</li>
 *   <li>starting empty means {@code skip-worktree} / {@code assume-unchanged} bits cannot be
 *       inherited, closing the "hide a file in the index" hole (§8.4 item 10).</li>
 * </ul>
 *
 * <p>{@code add -A} obeys exclusion inputs the agent controls, so the R1–R5 integrity rules run
 * alongside the capture: content that is absent from the tree is also absent from the reviewer's
 * diff, which is the "invisible exclusion" class (§3.3).
 */
public final class GitCliSnapshot implements SnapshotCapture {

    private final GitCli git;
    private final Path indexDir;

    public GitCliSnapshot(GitCli git, Path indexDir) {
        this.git = git;
        this.indexDir = indexDir;
    }

    @Override
    public Snapshot capture(RepoRef cloneRepo, RepoRef authRepo, String targetRef) {
        assertIndependentClone(cloneRepo);
        assertNoMergeInProgress(cloneRepo);

        ObjectId baseCommit = resolveAuthTip(authRepo, targetRef);
        ObjectId baseTree = ObjectId.of(git.line(authRepo, "rev-parse", "--verify", baseCommit.hex() + "^{tree}"));
        assertCloneOnBase(cloneRepo, targetRef, baseCommit);

        CaptureIntegrityReport integrity = checkIntegrity(cloneRepo, baseCommit);

        ObjectId treeHash = writeTreeWithTemporaryIndex(cloneRepo);
        List<String> changedPaths = changedPaths(cloneRepo, baseTree, treeHash);
        String diff = diffText(cloneRepo, baseTree, treeHash);

        return new Snapshot(treeHash, baseCommit, baseTree, targetRef, changedPaths, diff, integrity);
    }

    @Override
    public Snapshot rebuild(RepoRef cloneRepo, String targetRef, ObjectId treeHash, ObjectId baseCommit, String diff) {
        // Derive base tree and changed paths from the recorded tree/base only; never re-run add -A,
        // which would fold in post-presubmit worktree changes and defeat the TOCTOU check.
        ObjectId baseTree = ObjectId.of(git.line(cloneRepo, "rev-parse", "--verify", baseCommit.hex() + "^{tree}"));
        List<String> changedPaths = changedPaths(cloneRepo, baseTree, treeHash);
        return new Snapshot(treeHash, baseCommit, baseTree, targetRef, changedPaths, diff,
                CaptureIntegrityReport.clean());
    }

    /**
     * Writes the tree through a throwaway index file that is deleted afterwards.
     *
     * <p>The temporary index lives outside both repos so a crash cannot leave a stray index where
     * git would find it.
     */
    private ObjectId writeTreeWithTemporaryIndex(RepoRef cloneRepo) {
        Path tempIndex = indexDir.resolve("idx-" + UUID.randomUUID().toString().replace("-", ""));
        try {
            Files.createDirectories(indexDir);
            Files.deleteIfExists(tempIndex);
            Map<String, String> env = Map.of("GIT_INDEX_FILE", tempIndex.toAbsolutePath().toString());
            git.must(cloneRepo.path(), env, "add", "-A");
            String tree = git.must(cloneRepo.path(), env, "write-tree").stdout().trim();
            return ObjectId.of(tree);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot prepare temporary index " + tempIndex, e);
        } finally {
            try {
                Files.deleteIfExists(tempIndex);
            } catch (IOException ignored) {
                // A stale temp index outside the repo cannot affect correctness; the next capture
                // uses a fresh random name.
            }
        }
    }

    private ObjectId resolveAuthTip(RepoRef authRepo, String targetRef) {
        ProcessRunner.ProcRun run = git.run(authRepo, "rev-parse", "--verify", targetRef);
        if (!run.ok()) {
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "target ref " + targetRef + " does not exist in the authoritative repo; run `gate init` first");
        }
        return ObjectId.of(run.stdout().trim());
    }

    /**
     * The clone's local target branch must sit exactly on the authoritative tip.
     *
     * <p>If the base has moved, publishing later could only succeed by grafting the reviewed tree
     * onto a different parent, which is exactly what {@code parent==old} refuses in the hook. Better
     * to refuse here with a clear precondition error than to review work that can never land (§3.2,
     * exit 12).
     */
    private void assertCloneOnBase(RepoRef cloneRepo, String targetRef, ObjectId baseCommit) {
        ProcessRunner.ProcRun run = git.run(cloneRepo, "rev-parse", "--verify", "HEAD");
        if (!run.ok()) {
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "clone " + cloneRepo.pathString() + " has no HEAD commit");
        }
        ObjectId cloneHead = ObjectId.of(run.stdout().trim());
        if (!cloneHead.equals(baseCommit)) {
            throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                    "BASE_STALE: clone HEAD " + cloneHead.hex() + " != authoritative " + targetRef
                            + " tip " + baseCommit.hex() + "; re-sync the clone and presubmit again");
        }
    }

    /**
     * Refuses a clone that shares a ref store or object store with the authoritative repo.
     *
     * <p>A linked worktree marks {@code .git} as a <em>file</em>; inside one, a single
     * {@code update-ref} rewrites the authoritative branch with no push, no receive-pack and no
     * pre-receive — the gate is simply absent (B14, measured in P0). {@code alternates} pointing at
     * {@code auth.git} is the object-store analogue.
     */
    private void assertIndependentClone(RepoRef cloneRepo) {
        Path dotGit = cloneRepo.path().resolve(".git");
        if (!Files.exists(dotGit)) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "not a git repository: " + cloneRepo.pathString());
        }
        if (Files.isRegularFile(dotGit)) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "refusing to operate on a linked worktree (.git is a file): " + cloneRepo.pathString()
                            + " — a linked worktree shares the authoritative ref store and bypasses the gate entirely (B14)");
        }
        Path alternates = dotGit.resolve("objects").resolve("info").resolve("alternates");
        if (Files.exists(alternates)) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "refusing to operate on a clone with objects/info/alternates: " + cloneRepo.pathString()
                            + " — clone with --no-hardlinks so the object store is physically independent");
        }
    }

    /** Merge / cherry-pick in progress cannot be expressed by a single-parent tree snapshot (§1.4). */
    private void assertNoMergeInProgress(RepoRef cloneRepo) {
        for (String marker : List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD")) {
            if (Files.exists(cloneRepo.path().resolve(".git").resolve(marker))) {
                throw new GateException(GateErrorCode.REJECT_PRECONDITION,
                        "refusing presubmit while " + marker + " exists: the gate reviews single-parent "
                                + "tree snapshots and cannot express a merge");
            }
        }
    }

    /**
     * R1–R5 (§3.3).
     *
     * <p>Config lookups are scoped to {@code --local} and {@code --worktree} on purpose. A global
     * {@code core.excludesFile} belongs to the human running the gate, not to the agent, and
     * blocking on it would make the gate unusable on a normally configured workstation while adding
     * no security: the attack this rule closes is an agent writing exclusion inputs into the repo it
     * controls.
     */
    private CaptureIntegrityReport checkIntegrity(RepoRef cloneRepo, ObjectId baseCommit) {
        List<IntegrityViolation> violations = new ArrayList<>();

        // R1: .git/info/exclude non-empty
        Path exclude = cloneRepo.path().resolve(".git").resolve("info").resolve("exclude");
        if (Files.exists(exclude)) {
            try {
                String body = Files.readString(exclude);
                boolean meaningful = body.lines()
                        .map(String::trim)
                        .anyMatch(l -> !l.isEmpty() && !l.startsWith("#"));
                if (meaningful) {
                    violations.add(new IntegrityViolation("R1", IntegritySeverity.BLOCKER,
                            ".git/info/exclude is non-empty; content it excludes would be invisible to the reviewer"));
                }
            } catch (IOException e) {
                violations.add(new IntegrityViolation("R1", IntegritySeverity.BLOCKER,
                        "cannot read .git/info/exclude: " + e.getMessage()));
            }
        }

        // R2: core.excludesFile set in repo-scoped config
        String excludesFile = localConfig(cloneRepo, "core.excludesFile");
        if (excludesFile != null && !excludesFile.isBlank()) {
            violations.add(new IntegrityViolation("R2", IntegritySeverity.BLOCKER,
                    "repo-scoped core.excludesFile is set to '" + excludesFile + "'"));
        }

        // R3: filter.*.clean or core.hooksPath in repo-scoped config
        for (String entry : localConfigList(cloneRepo)) {
            String key = entry.contains("=") ? entry.substring(0, entry.indexOf('=')) : entry;
            if (key.startsWith("filter.") && key.endsWith(".clean")) {
                violations.add(new IntegrityViolation("R3", IntegritySeverity.BLOCKER,
                        "clean filter configured: " + key + " — it can rewrite content between worktree and tree"));
            }
            if (key.equals("core.hookspath")) {
                violations.add(new IntegrityViolation("R3", IntegritySeverity.BLOCKER,
                        "repo-scoped core.hooksPath is set: " + entry));
            }
        }

        // R4: tracked .gitignore fingerprint differs from base
        String currentIgnoreFp = fingerprintTrackedIgnores(cloneRepo, null);
        String baseIgnoreFp = fingerprintTrackedIgnores(cloneRepo, baseCommit);
        if (!currentIgnoreFp.equals(baseIgnoreFp)) {
            violations.add(new IntegrityViolation("R4", IntegritySeverity.WARNING,
                    "tracked .gitignore changed relative to base (base=" + baseIgnoreFp
                            + ", now=" + currentIgnoreFp + "); the .gitignore diff is part of the review"));
        }

        // R5: ignored set relative to base
        List<String> ignored = ignoredPaths(cloneRepo);
        if (!ignored.isEmpty() && !currentIgnoreFp.equals(baseIgnoreFp)) {
            violations.add(new IntegrityViolation("R5", IntegritySeverity.WARNING,
                    "ignored paths present while .gitignore changed: " + String.join(", ", ignored)));
        }

        return new CaptureIntegrityReport(violations);
    }

    private String localConfig(RepoRef cloneRepo, String key) {
        ProcessRunner.ProcRun run = git.run(cloneRepo, "config", "--local", "--get", key);
        return run.ok() ? run.stdout().trim() : null;
    }

    private List<String> localConfigList(RepoRef cloneRepo) {
        ProcessRunner.ProcRun run = git.run(cloneRepo, "config", "--local", "--list");
        if (!run.ok()) {
            return List.of();
        }
        return run.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Fingerprint of every tracked {@code .gitignore}: {@code path=blobsha} pairs joined by ';'.
     *
     * @param at {@code null} for the working tree state, otherwise the commit to inspect
     */
    private String fingerprintTrackedIgnores(RepoRef cloneRepo, ObjectId at) {
        ProcessRunner.ProcRun run = at == null
                ? git.run(cloneRepo, "ls-files", "-s", "--", "*.gitignore", ".gitignore")
                : git.run(cloneRepo, "ls-tree", "-r", at.hex());
        if (!run.ok()) {
            return "";
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String line : run.stdout().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // "<mode> <sha> <stage>\t<path>" (ls-files -s) or "<mode> blob <sha>\t<path>" (ls-tree)
            int tab = trimmed.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String path = trimmed.substring(tab + 1).trim();
            if (!path.equals(".gitignore") && !path.endsWith("/.gitignore")) {
                continue;
            }
            String[] head = trimmed.substring(0, tab).split("\\s+");
            String sha = head.length >= 3 ? head[head.length - 2] : "";
            if (at == null && head.length >= 3) {
                sha = head[1];
            }
            parts.add(path + "=" + sha);
        }
        return String.join(";", parts);
    }

    private List<String> ignoredPaths(RepoRef cloneRepo) {
        ProcessRunner.ProcRun run = git.run(cloneRepo, "ls-files", "-o", "-i", "--exclude-standard");
        if (!run.ok()) {
            return List.of();
        }
        return run.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> changedPaths(RepoRef cloneRepo, ObjectId baseTree, ObjectId tree) {
        ProcessRunner.ProcRun run = git.must(cloneRepo,
                "diff-tree", "-r", "--no-commit-id", "--name-only", baseTree.hex(), tree.hex());
        return run.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String diffText(RepoRef cloneRepo, ObjectId baseTree, ObjectId tree) {
        return git.must(cloneRepo, "diff", "--no-color", "--find-renames", baseTree.hex(), tree.hex()).stdout();
    }
}
