package gate.adapters.git;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.ports.ProcessRunner;
import gate.ports.RefObserver;
import java.util.Optional;

/** Read-only view of a repository's refs, backed by the real git binary. */
public final class GitCliRefObserver implements RefObserver {

    private final GitCli git;

    public GitCliRefObserver(GitCli git) {
        this.git = git;
    }

    @Override
    public Optional<ObjectId> tip(RepoRef repo, String ref) {
        ProcessRunner.ProcRun run = git.run(repo, "rev-parse", "--verify", ref);
        if (!run.ok()) {
            return Optional.empty();
        }
        String out = run.stdout().trim();
        return out.isEmpty() ? Optional.empty() : Optional.of(ObjectId.of(out));
    }

    /**
     * {@code git merge-base --is-ancestor} exits 0 for "yes" and 1 for "no". Any other exit is a
     * genuine git failure — e.g. a missing object — and must not be read as "no", because that would
     * silently report an unpublished commit as published.
     *
     * <p>One legitimate "missing object" case must NOT throw: after a crash before push, the
     * commit exists only in the clone, never in {@code auth.git}. A commit that is absent from the
     * repo cannot be an ancestor of anything in it, so an explicit existence probe returns false
     * rather than letting the 128 exit escalate to an IO error.
     */
    @Override
    public boolean isAncestor(RepoRef repo, ObjectId maybeAncestor, ObjectId tip) {
        if (!objectExists(repo, maybeAncestor) || !objectExists(repo, tip)) {
            return false;
        }
        ProcessRunner.ProcRun run = git.run(repo, "merge-base", "--is-ancestor", maybeAncestor.hex(), tip.hex());
        if (run.exitCode() == 0 && !run.timedOut()) {
            return true;
        }
        if (run.exitCode() == 1 && !run.timedOut()) {
            return false;
        }
        throw new gate.domain.error.GateException(gate.domain.error.GateErrorCode.GATE_ERROR_IO,
                "merge-base --is-ancestor failed (exit=" + run.exitCode() + "): " + run.stderrFirstLine());
    }

    /** {@code git cat-file -e <obj>}: exit 0 iff the object exists in this repo. */
    private boolean objectExists(RepoRef repo, ObjectId obj) {
        return git.run(repo, "cat-file", "-e", obj.hex() + "^{commit}").exitCode() == 0;
    }

    @Override
    public long countCommits(RepoRef repo, String ref) {
        ProcessRunner.ProcRun run = git.run(repo, "rev-list", "--count", ref);
        if (!run.ok()) {
            return 0L;
        }
        String out = run.stdout().trim();
        return out.isEmpty() ? 0L : Long.parseLong(out);
    }
}
