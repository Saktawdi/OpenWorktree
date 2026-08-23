package gate.ports;

import gate.domain.git.RepoRef;
import java.nio.file.Path;

/**
 * Creates the repository topology of 架构落地执行文档 §2.1.
 *
 * <p>Two constraints are load-bearing and both were learned the hard way in P0:
 * <ul>
 *   <li>after {@code git init --bare}, HEAD must be explicitly pointed at the target branch
 *       ({@code symbolic-ref}); git 2.37 defaults to {@code master} and a clone would otherwise
 *       land on an empty branch (N1);</li>
 *   <li>agent repos are always {@code clone --no-hardlinks --single-branch --branch <target>},
 *       never {@code git worktree add} — a linked worktree shares the ref store, and one
 *       {@code update-ref} inside it rewrites the authoritative branch with no push, no
 *       receive-pack and no pre-receive (B14, ADR-3).</li>
 * </ul>
 */
public interface TopologyInitializer {

    /**
     * Initialises {@code auth.git}, seeds the base commit, then installs the hook.
     *
     * <p>Seeding happens <em>before</em> the hook is installed: the hook requires an approval bound
     * to {@code (ref, old, new, tree)} and demands exactly one parent, so a root commit can never
     * pass it. The base commit is therefore bootstrap, not a ticket product — this is the A1
     * accounting baseline (the ticket must add exactly one commit on top of it).
     *
     * @return the seeded base commit
     */
    InitResult initAuthRepo(RepoRef authRepo, String targetRef, Path approvalsDir);

    /** {@code clone --no-hardlinks --single-branch --branch <target>}. */
    RepoRef createClone(RepoRef authRepo, String targetRef, Path cloneDir);

    /**
     * 确保权威库存在 {@code targetRef} 分支：缺失时从 {@code baseRef} 的 tip 建支，已存在则不动。
     *
     * <p>工单级目标分支（默认 {@code refs/heads/<工单号>}）的克隆前提；服务端 {@code update-ref}
     * 建支不经过 pre-receive——这是与种子提交同一性质的 bootstrap 动作，不是工单产物提交。
     */
    default void ensureBranch(RepoRef authRepo, String targetRef, String baseRef) {
    }

    record InitResult(gate.domain.git.ObjectId baseCommit, String hookSha256) {
    }
}
