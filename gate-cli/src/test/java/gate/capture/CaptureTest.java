package gate.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.domain.error.GateException;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.testkit.GateHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Capture-time invariants (架构落地执行文档 §3.2/§3.3, ADR-5). These are the measured P0 facts turned
 * into assertions.
 */
@Tag("slow")
class CaptureTest {

    /**
     * ADR-5: capture uses a fresh temporary index and must not touch the agent's real index. The
     * proof is byte-level: the real index file is identical before and after presubmit.
     */
    @Test
    void captureDoesNotPolluteAgentIndex() throws Exception {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            // Stage something in the agent's REAL index, plus leave an untracked file.
            h.writeFile(clone, "tracked.txt", "staged content\n");
            h.git(clone, "add", "tracked.txt");
            h.writeFile(clone, "untracked.txt", "not staged\n");

            Path realIndex = clone.path().resolve(".git").resolve("index");
            byte[] before = Files.readAllBytes(realIndex);

            PresubmitResult result = h.service().presubmit(new PresubmitCommand("TICKET-1"));

            byte[] after = Files.readAllBytes(realIndex);
            assertEquals(bytesToHex(sha256(before)), bytesToHex(sha256(after)),
                    "agent real index must be byte-identical before and after capture (ADR-5)");
            // And the capture still saw the untracked file (add -A into the temp index).
            assertTrue(result.changedPaths().contains("untracked.txt"),
                    "temp-index capture must include untracked content");
            assertTrue(result.changedPaths().contains("tracked.txt"));
        }
    }

    /**
     * Empty diff (tree == base tree) must be refused AND must not consume a review round (§3.2).
     * commit-tree happily builds an empty-diff commit, so the application must catch it.
     */
    @Test
    void emptyDiffRejectedWithoutConsumingRound() {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            // No worktree change at all -> tree == base tree.
            GateException ex = assertThrows(GateException.class,
                    () -> h.service().presubmit(new PresubmitCommand("TICKET-1")));
            assertTrue(ex.getMessage().toLowerCase().contains("empty diff"), ex.getMessage());

            // Now make a real change and presubmit: it must be round 1, proving the empty attempt
            // consumed nothing.
            h.writeFile(clone, "real.txt", "real change\n");
            PresubmitResult r = h.service().presubmit(new PresubmitCommand("TICKET-1"));
            assertEquals(1, r.reviewRound(), "empty diff must not have consumed review round 1");
        }
    }

    /**
     * {@code core.autocrlf} changes {@code tree_hash} for identical bytes (measured, §0.1). The gate
     * pins {@code core.autocrlf=false} on every invocation; this test proves the setting actually
     * matters, so pinning is load-bearing rather than cosmetic.
     */
    @Test
    void autocrlfChangesTreeHash() {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            Path file = clone.path().resolve("crlf.txt");
            try {
                Files.write(file, "line1\r\nline2\r\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            String treeFalse = writeTreeWith(h, clone, "false");
            String treeTrue = writeTreeWith(h, clone, "true");
            assertNotEquals(treeFalse, treeTrue,
                    "the same CRLF bytes must produce different trees under autocrlf false vs true (§0.1)");
        }
    }

    /** R1: a non-empty {@code .git/info/exclude} blocks presubmit (§3.3). */
    @Test
    void gitignoreInvisibilityR1BlocksPresubmit() throws Exception {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            h.writeFile(clone, "real.txt", "x\n");
            Path exclude = clone.path().resolve(".git").resolve("info").resolve("exclude");
            Files.writeString(exclude, "secret.key\n");

            GateException ex = assertThrows(GateException.class,
                    () -> h.service().presubmit(new PresubmitCommand("TICKET-1")));
            assertTrue(ex.getMessage().contains("R1"), ex.getMessage());
        }
    }

    /** R3: a repo-scoped clean filter blocks presubmit — it can rewrite content between worktree and tree. */
    @Test
    void cleanFilterR3BlocksPresubmit() {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            h.writeFile(clone, "real.txt", "x\n");
            h.git(clone, "config", "filter.evil.clean", "sed s/x/y/");

            GateException ex = assertThrows(GateException.class,
                    () -> h.service().presubmit(new PresubmitCommand("TICKET-1")));
            assertTrue(ex.getMessage().contains("R3"), ex.getMessage());
        }
    }

    /**
     * R4 regression: the base fingerprint reads {@code ls-tree} ("<mode> blob <sha>"); parsing the
     * type column as the sha made every presubmit with a tracked .gitignore warn spuriously.
     */
    @Test
    void r4WarnsOnlyWhenTrackedGitignoreActuallyChanged() throws Exception {
        try (GateHarness h = new GateHarness()) {
            RepoRef clone = h.createTicket("TICKET-1");
            h.writeFile(clone, "real.txt", "x\n");

            PresubmitResult unchanged = h.service().presubmit(new PresubmitCommand("TICKET-1"));
            assertTrue(unchanged.integrity().warnings().stream().noneMatch(v -> v.rule().equals("R4")),
                    "unchanged tracked .gitignore must not raise R4: " + unchanged.integrity().warnings());

            h.writeFile(clone, ".gitignore", "secret.key\n");
            h.git(clone, "add", ".gitignore");

            PresubmitResult changed = h.service().presubmit(new PresubmitCommand("TICKET-1"));
            assertTrue(changed.integrity().warnings().stream().anyMatch(v -> v.rule().equals("R4")),
                    "a changed tracked .gitignore must raise R4");
        }
    }

    private String writeTreeWith(GateHarness h, RepoRef clone, String autocrlf) {
        Path tempIndex = clone.path().resolve(".git").resolve("test-index-" + UUID.randomUUID());
        Map<String, String> env = Map.of("GIT_INDEX_FILE", tempIndex.toString());
        // Explicitly override the pinned default for this probe.
        h.gitIn(clone.path(), env, "-c", "core.autocrlf=" + autocrlf, "add", "-A");
        String tree = h.gitIn(clone.path(), env, "-c", "core.autocrlf=" + autocrlf, "write-tree").stdout().trim();
        return ObjectId.of(tree).hex();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String bytesToHex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
