package gate.adapters.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.ports.WorkspaceSyncer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-git coverage for the non-destructive, fast-forward-only workspace projection. */
class GitCliWorkspaceSyncerTest {

    @TempDir
    Path root;

    @Test
    void creates_a_missing_target_branch() throws Exception {
        Fixture f = fixture();
        ObjectId published = advanceAuth(f, "upstream\n");
        Path other = Files.createDirectories(root.resolve("other"));
        git().must(other, Map.of(), "init", "-b", "scratch");

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(RepoRef.of(other), f.auth(), MAIN, published);

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.SYNCED, outcome.status());
        assertEquals(published.hex(), git().line(RepoRef.of(other), "rev-parse", "--verify", MAIN));
    }

    @Test
    void fast_forwards_a_behind_unchecked_out_branch() throws Exception {
        Fixture f = fixture();
        git().must(f.workspace(), "checkout", "-b", "work");
        ObjectId published = advanceAuth(f, "upstream\n");

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(f.workspace(), f.auth(), MAIN, published);

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.SYNCED, outcome.status());
        assertEquals(published.hex(), git().line(f.workspace(), "rev-parse", "--verify", MAIN));
    }

    @Test
    void fast_forwards_a_clean_checked_out_branch() throws Exception {
        Fixture f = fixture();
        ObjectId published = advanceAuth(f, "upstream\n");

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(f.workspace(), f.auth(), MAIN, published);

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.SYNCED, outcome.status());
        assertEquals(published.hex(), git().line(f.workspace(), "rev-parse", "--verify", "HEAD"));
    }

    @Test
    void defers_a_dirty_checked_out_branch() throws Exception {
        Fixture f = fixture();
        Files.writeString(f.workspace().path().resolve("README.md"), "local edit\n");
        ObjectId published = advanceAuth(f, "upstream\n");

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(f.workspace(), f.auth(), MAIN, published);

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.DEFERRED, outcome.status());
        assertTrue(outcome.note().contains("checked out with local changes"), outcome.note());
        assertEquals(f.initial().hex(), git().line(f.workspace(), "rev-parse", "--verify", "HEAD"));
    }

    @Test
    void defers_a_diverged_branch() throws Exception {
        Fixture f = fixture();
        commit(f.workspace(), "README.md", "local branch\n", "local");
        ObjectId published = advanceAuth(f, "upstream\n");

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(f.workspace(), f.auth(), MAIN, published);

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.DEFERRED, outcome.status());
        assertTrue(outcome.note().contains("diverged"), outcome.note());
        assertFalse(git().line(f.workspace(), "rev-parse", "--verify", "HEAD").equals(published.hex()));
    }

    @Test
    void reports_already_when_workspace_tip_matches() throws Exception {
        Fixture f = fixture();

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(f.workspace(), f.auth(), MAIN, f.initial());

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.ALREADY, outcome.status());
    }

    @Test
    void defers_a_non_git_directory() throws Exception {
        Fixture f = fixture();
        Path plain = Files.createDirectories(root.resolve("plain"));

        WorkspaceSyncer.SyncOutcome outcome = sync().syncWorkspace(RepoRef.of(plain), f.auth(), MAIN, f.initial());

        assertEquals(WorkspaceSyncer.SyncOutcome.Status.DEFERRED, outcome.status());
        assertTrue(outcome.note().contains("not a git repository"), outcome.note());
    }

    private Fixture fixture() throws Exception {
        Path auth = root.resolve("auth.git");
        Path source = root.resolve("source");
        Path workspace = root.resolve("workspace");
        GitCli git = git();
        git.must(root, Map.of(), "init", "--bare", "-b", "main", auth.toString());
        git.must(root, Map.of(), "init", "-b", "main", source.toString());
        RepoRef sourceRepo = RepoRef.of(source);
        configureIdentity(sourceRepo);
        commit(sourceRepo, "README.md", "initial\n", "initial");
        git.must(sourceRepo, "remote", "add", "origin", auth.toString());
        git.must(sourceRepo, "push", "origin", "main");
        git.must(root, Map.of(), "clone", auth.toString(), workspace.toString());
        RepoRef workspaceRepo = RepoRef.of(workspace);
        configureIdentity(workspaceRepo);
        ObjectId initial = ObjectId.of(git.line(RepoRef.of(auth), "rev-parse", "--verify", MAIN));
        return new Fixture(RepoRef.of(auth), sourceRepo, workspaceRepo, initial);
    }

    private ObjectId advanceAuth(Fixture f, String contents) throws Exception {
        commit(f.source(), "README.md", contents, "upstream");
        git().must(f.source(), "push", "origin", "main");
        return ObjectId.of(git().line(f.auth(), "rev-parse", "--verify", MAIN));
    }

    private void commit(RepoRef repo, String file, String contents, String message) throws Exception {
        Files.writeString(repo.path().resolve(file), contents);
        git().must(repo, "add", "-A");
        git().must(repo, "commit", "-m", message);
    }

    private void configureIdentity(RepoRef repo) {
        git().must(repo, "config", "user.name", "gate test");
        git().must(repo, "config", "user.email", "gate-test@localhost");
    }

    private GitCliWorkspaceSyncer sync() {
        return new GitCliWorkspaceSyncer(git());
    }

    private GitCli git() {
        return new GitCli(new gate.adapters.process.ProcessRunnerImpl(root.resolve("proc")));
    }

    private record Fixture(RepoRef auth, RepoRef source, RepoRef workspace, ObjectId initial) {
    }

    private static final String MAIN = "refs/heads/main";
}
