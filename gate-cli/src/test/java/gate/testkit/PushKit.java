package gate.testkit;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.publish.ApprovalGrant;
import gate.domain.publish.ApprovalId;
import gate.ports.infra.ProcessRunner;
import java.util.Map;

/**
 * Helpers shared by the bypass suite for constructing pushes — both legitimate and hostile —
 * against a {@link GateHarness}.
 *
 * <p>Everything here uses the real git binary with the same pinned config as production, because the
 * adversary this project models uses real git (§11.1). A push that the harness itself makes with a
 * genuine approval is the "happy path"; every other method models an attack.
 */
public final class PushKit {

    private final GateHarness h;

    public PushKit(GateHarness h) {
        this.h = h;
    }

    /** Stages everything in a temp index and writes the tree, without touching the real index. */
    public String writeTree(RepoRef clone) {
        String idx = clone.path().resolve(".git").resolve("pk-idx-" + System.nanoTime()).toString();
        Map<String, String> env = Map.of("GIT_INDEX_FILE", idx);
        h.gitIn(clone.path(), env, "add", "-A");
        return h.gitIn(clone.path(), env, "write-tree").stdout().trim();
    }

    /** Builds a deterministic commit on top of {@code parent}. */
    public String commitTree(RepoRef clone, String tree, String parent, String message) {
        return h.gitIn(clone.path(), h.identityEnv(), "commit-tree", tree, "-p", parent, "-m", message)
                .stdout().trim();
    }

    /** Builds a root commit (no parent) — used to prove the hook rejects zero-parent commits. */
    public String commitTreeNoParent(RepoRef clone, String tree, String message) {
        return h.gitIn(clone.path(), h.identityEnv(), "commit-tree", tree, "-m", message).stdout().trim();
    }

    /** Issues a genuine approval record bound to the exact transition. */
    public ApprovalId issueApproval(String ref, String oldCommit, String newCommit, String tree) {
        ApprovalId id = h.approvalStore().allocate();
        h.approvalStore().issue(id, new ApprovalGrant(ref, ObjectId.of(oldCommit), ObjectId.of(newCommit),
                ObjectId.of(tree)));
        return id;
    }

    /** Push with a gate-approval push-option. */
    public ProcessRunner.ProcRun pushWithApproval(RepoRef clone, ApprovalId id, String commit, String ref) {
        return h.gitIn(clone.path(), Map.of(),
                "push", "--push-option=gate-approval=" + id.value(),
                h.authRepo().pathString(), commit + ":" + ref);
    }

    /** Push with a raw push-option string (for malformed / traversal ids). */
    public ProcessRunner.ProcRun pushWithRawOption(RepoRef clone, String option, String commit, String ref) {
        return h.gitIn(clone.path(), Map.of(),
                "push", "--push-option=" + option, h.authRepo().pathString(), commit + ":" + ref);
    }

    /** Push with two push-options, to exercise judgement ① (COUNT != 1). */
    public ProcessRunner.ProcRun pushWithTwoOptions(RepoRef clone, String o1, String o2, String commit, String ref) {
        return h.gitIn(clone.path(), Map.of(),
                "push", "--push-option=" + o1, "--push-option=" + o2,
                h.authRepo().pathString(), commit + ":" + ref);
    }

    /** Push with no push-option at all. */
    public ProcessRunner.ProcRun pushNoOption(RepoRef clone, String commit, String ref) {
        return h.gitIn(clone.path(), Map.of(),
                "push", h.authRepo().pathString(), commit + ":" + ref);
    }

    /** Force push. */
    public ProcessRunner.ProcRun forcePush(RepoRef clone, ApprovalId id, String commit, String ref) {
        return h.gitIn(clone.path(), Map.of(),
                "push", "--force", "--push-option=gate-approval=" + id.value(),
                h.authRepo().pathString(), commit + ":" + ref);
    }
}
