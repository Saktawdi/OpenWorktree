package gate.adapters.git;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.ports.ProcessRunner;
import gate.ports.WorkspaceSyncer;
import java.nio.file.Files;

/**
 * 通过真实 git 将权威提交尽力快进回写到用户工作区（ADR-1）。
 *
 * <p>此适配器只更新目标本地分支：已检出的分支仅在工作区干净时执行
 * {@code merge --ff-only}，未检出分支使用带旧值的 {@code update-ref}。任何失败均降级为
 * {@code DEFERRED}，从不改变已经完成的发布结果。
 */
public final class GitCliWorkspaceSyncer implements WorkspaceSyncer {

    private final GitCli git;

    public GitCliWorkspaceSyncer(GitCli git) {
        this.git = git;
    }

    @Override
    public SyncOutcome syncWorkspace(RepoRef workspaceRepo, RepoRef authRepo, String targetRef, ObjectId commit) {
        try {
            if (!Files.isDirectory(workspaceRepo.path()) || !Files.exists(workspaceRepo.path().resolve(".git"))) {
                return deferred("workspace is not a git repository: " + workspaceRepo.pathString());
            }
            if (targetRef == null || !targetRef.startsWith("refs/heads/")
                    || targetRef.length() == "refs/heads/".length()) {
                return deferred("target ref is not a branch: " + targetRef);
            }

            String branch = targetRef.substring("refs/heads/".length());
            ProcessRunner.ProcRun fetch = git.run(workspaceRepo, "fetch", authRepo.pathString(), branch);
            if (!fetch.ok()) {
                return deferred(firstFailure("fetch", fetch));
            }

            ProcessRunner.ProcRun reachable = git.run(workspaceRepo, "rev-parse", "--verify",
                    commit.hex() + "^{commit}");
            if (!reachable.ok()) {
                return deferred("commit " + commit.hex() + " not reachable after fetch");
            }

            ProcessRunner.ProcRun tipRun = git.run(workspaceRepo, "rev-parse", "--verify", targetRef);
            String tip = tipRun.ok() ? tipRun.stdout().trim() : null;
            if (commit.hex().equals(tip)) {
                return new SyncOutcome(SyncOutcome.Status.ALREADY, null);
            }
            if (tip != null) {
                ProcessRunner.ProcRun ancestor = git.run(workspaceRepo, "merge-base", "--is-ancestor", tip,
                        commit.hex());
                if (!ancestor.ok()) {
                    return deferred("workspace " + targetRef + " diverged from auth " + authRepo.pathString()
                            + "; manual merge required");
                }
            }

            ProcessRunner.ProcRun checkedOut = git.run(workspaceRepo, "symbolic-ref", "-q", "HEAD");
            boolean targetCheckedOut = checkedOut.ok() && targetRef.equals(checkedOut.stdout().trim());
            if (targetCheckedOut) {
                ProcessRunner.ProcRun status = git.run(workspaceRepo, "status", "--porcelain");
                if (!status.ok()) {
                    return deferred(firstFailure("status", status));
                }
                if (!status.stdout().isBlank()) {
                    return deferred("workspace branch " + branch
                            + " is checked out with local changes; not fast-forwarding");
                }
                ProcessRunner.ProcRun merge = git.run(workspaceRepo, "merge", "--ff-only", commit.hex());
                if (!merge.ok()) {
                    return deferred(firstFailure("merge --ff-only", merge));
                }
            } else {
                ProcessRunner.ProcRun update = tip == null
                        ? git.run(workspaceRepo, "update-ref", targetRef, commit.hex())
                        : git.run(workspaceRepo, "update-ref", targetRef, commit.hex(), tip);
                if (!update.ok()) {
                    return deferred(firstFailure("update-ref", update));
                }
            }
            return new SyncOutcome(SyncOutcome.Status.SYNCED, targetRef + " -> " + commit.hex().substring(0, 8));
        } catch (Exception e) {
            String message = e.getMessage();
            return deferred(message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }
    }

    private static SyncOutcome deferred(String note) {
        return new SyncOutcome(SyncOutcome.Status.DEFERRED, note);
    }

    private static String firstFailure(String operation, ProcessRunner.ProcRun run) {
        String detail = run.stderrFirstLine();
        return detail == null || detail.isBlank() ? operation + " failed (exit=" + run.exitCode() + ")" : detail;
    }
}
