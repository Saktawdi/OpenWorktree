package gate.cli;

import gate.domain.git.RepoRef;
import gate.ports.TopologyInitializer;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate init}: create/repair the authoritative bare repo, seed the base commit, install the
 * hook (§2, §10.3 ordering). The base commit is bootstrap infrastructure — the A1 accounting
 * baseline on top of which a ticket adds exactly one commit.
 */
@CommandLine.Command(name = "init", description = "Initialise auth.git, seed base, install pre-receive hook")
final class InitCommand extends BaseCommand {

    @Override
    public void run() {
        GateComponents c = components();
        String targetRef = c.config().primaryTargetRef();
        RepoRef auth = RepoRef.of(c.config().authRepo());
        TopologyInitializer.InitResult result =
                c.topologyInitializer().initAuthRepo(auth, targetRef, c.config().approvalsDir());
        JsonOut.emit(System.out, "init", Map.of(
                "auth_repo", auth.pathString(),
                "target_ref", targetRef,
                "base_commit", result.baseCommit().hex(),
                "hook_sha256", result.hookSha256()));
    }
}
