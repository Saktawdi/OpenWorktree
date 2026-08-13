package gate.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import gate.adapters.mcp.McpServer;
import gate.application.ReviewCommand;
import gate.domain.config.GateConfig;
import gate.domain.git.RepoRef;
import gate.testkit.GateHarness;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A11 real-CLI smoke test (执行文档 §4 P3 A11 — the real-binary counterpart of {@link
 * McpEndToEndTest}).
 *
 * <p>Where {@link McpEndToEndTest} drives the MCP server in-process through in-memory pipes, this
 * test spawns the packaged fat jar as a <b>real child process</b> — {@code java -jar gate.jar mcp
 * serve -c gate.toml} — and drives the full {@code presubmit → reject → fix → pass → publish} loop
 * over that child's real stdin/stdout pipes. It proves the wiring survives Spring Boot startup,
 * {@code TomlGateConfigLoader}, the {@code GATE_DOMAIN_TOKEN} env-var handshake, and one JSON-RPC
 * message per line over an actual OS pipe.
 *
 * <p><b>Two-domain shape.</b> The agent-domain child spawns with an agent token and may only
 * {@code presubmit_create} / {@code review_result_get}. The human verdict (review) is minted by
 * {@code GatePolicy} through the shared {@link gate.application.GateService} — the same manual-verdict
 * shortcut {@link McpEndToEndTest} uses, since the child processes and the harness all share one
 * gate-home (auth repo, SQLite DB, blob store). The publish is driven through a second child spawned
 * with a human token, exercising the real {@code commit_and_publish} tool over its own pipe.
 *
 * <p>Manual review engine only — no prism, no newapi, no external service. Tagged {@code smoke} so
 * it can be included or excluded selectively.
 */
@Tag("smoke")
class A11RealCliSmokeTest {

    /** JDK used to launch the child JVM (MUST set JAVA_HOME in the child, task requirement §3). */
    private static final String JDK_HOME =
            System.getProperty("gate.jdk.home", "C:\\Users\\17428\\.jdks\\corretto-17.0.3-1");

    private GateHarness harness;
    private Path fatJar;
    private Path configPath;

    @BeforeEach
    void setUp() throws IOException {
        harness = new GateHarness();
        harness.createTicket("T-1");
        fatJar = resolveFatJar();
        configPath = writeGateToml(harness.config(), harness.root());
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * The full A11 loop over real subprocess pipes:
     * <ol>
     *   <li>agent child: {@code presubmit_create} (round 1)</li>
     *   <li>human verdict via the shared service → manual REJECT (round 1)</li>
     *   <li>agent child: {@code review_result_get} → sees REJECT</li>
     *   <li>agent fixes the worktree, {@code presubmit_create} (round 2)</li>
     *   <li>human verdict via the shared service → manual PASS (round 2)</li>
     *   <li>human child: {@code commit_and_publish} → exactly one commit lands on auth</li>
     * </ol>
     */
    @Test
    void full_loop_over_real_subprocess_pipes() throws Exception {
        RepoRef clone = RepoRef.of(harness.config().clonesRoot().resolve("T-1"));
        String agentToken = harness.credentials().issueAgentToken("T-1", Instant.now());
        String humanToken = harness.credentials().issueHumanToken(Instant.now());

        long commitsBefore = harness.authCommitCount();

        try (ChildMcpServer agent = spawn(agentToken, "agent")) {
            agent.initialize();

            // --- Round 1: agent submits buggy work over the real pipe ---
            harness.writeFile(clone, "feature.txt", "buggy first attempt\n");
            String r1 = agent.call("presubmit_create", "{\"ticket_no\":\"T-1\"}");
            assertFalse(r1.contains("\"error\""), "presubmit_create round 1 must not error: " + r1);
            assertTrue(r1.contains("review_round"), "presubmit must report a round: " + r1);

            // Human verdict: manual REJECT through the shared service (GatePolicy mints the verdict).
            harness.service().review(new ReviewCommand("T-1", null, false, "fix the bug"));

            // Agent reads the rejection over the pipe.
            String rr = agent.call("review_result_get", "{\"ticket_no\":\"T-1\"}");
            assertTrue(rr.contains("REJECT"), "agent must see REJECT verdict over the pipe: " + rr);

            // --- Round 2: agent fixes and resubmits ---
            harness.writeFile(clone, "feature.txt", "fixed and correct\n");
            String r2 = agent.call("presubmit_create", "{\"ticket_no\":\"T-1\"}");
            assertFalse(r2.contains("\"error\""), "presubmit_create round 2 must not error: " + r2);
            assertTrue(r2.contains("review_round") && r2.contains(":2"),
                    "second presubmit must be round 2: " + r2);

            // Human verdict: manual PASS.
            harness.service().review(new ReviewCommand("T-1", null, true, null));
        }

        // Human publishes through the real commit_and_publish tool on a second child process.
        try (ChildMcpServer human = spawn(humanToken, "human")) {
            human.initialize();
            String pub = human.call("commit_and_publish", "{\"ticket_no\":\"T-1\"}");
            assertFalse(pub.contains("\"error\""), "commit_and_publish must not error: " + pub);
            assertTrue(pub.contains("commit_sha"), "publish must return a commit sha: " + pub);
        }

        assertEquals(commitsBefore + 1, harness.authCommitCount(),
                "exactly one commit must land on auth after the full real-CLI loop");
    }

    // --- subprocess plumbing ---------------------------------------------------------------------

    private ChildMcpServer spawn(String token, String label) throws IOException {
        Path logFile = harness.root().resolve("mcp-" + label + ".log");
        ProcessBuilder pb = new ProcessBuilder(
                javaBinary(), "-jar", fatJar.toString(),
                "mcp", "serve", "-c", configPath.toString());
        // stdout stays a pure JSON-RPC channel; all diagnostics (Spring/slf4j/gate-mcp) go to the log.
        pb.redirectError(logFile.toFile());
        Map<String, String> env = pb.environment();
        env.put("JAVA_HOME", JDK_HOME);
        env.put(McpServer.TOKEN_ENV, token);
        Process process = pb.start();
        return new ChildMcpServer(process, logFile);
    }

    /** The child JVM's {@code java} binary, resolved from {@link #JDK_HOME}. */
    private static String javaBinary() {
        boolean windows = File_separatorIsBackslash();
        String exe = windows ? "java.exe" : "java";
        Path candidate = Path.of(JDK_HOME, "bin", exe);
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        // Fall back to the JVM currently running the test if the configured JDK is unavailable.
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static boolean File_separatorIsBackslash() {
        return java.io.File.separatorChar == '\\';
    }

    /**
     * Locates {@code gate-cli/target/gate.jar}. Surefire runs with the module dir as the working
     * directory, so {@code target/gate.jar} resolves there; the {@code gate-cli/target/...} form
     * covers a reactor build launched from the repo root.
     */
    private static Path resolveFatJar() {
        String override = System.getProperty("gate.fat.jar");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        List<Path> candidates = List.of(
                Path.of("target", "gate.jar"),
                Path.of("gate-cli", "target", "gate.jar"));
        for (Path c : candidates) {
            Path abs = c.toAbsolutePath().normalize();
            if (Files.isRegularFile(abs)) {
                return abs;
            }
        }
        throw new IllegalStateException("cannot find gate.jar (looked in target/ and gate-cli/target/); "
                + "run `mvn -pl gate-cli package` first, or set -Dgate.fat.jar=<path>");
    }

    /**
     * Writes a {@code gate.toml} whose paths mirror {@link GateConfig} exactly, so the child process
     * opens the same gate-home the harness built. Paths are emitted with forward slashes so the flat
     * TOML parser (which does no backslash-escape processing) round-trips them on Windows.
     */
    private static Path writeGateToml(GateConfig cfg, Path root) throws IOException {
        Path toml = root.resolve("gate.toml");
        String whitelist = cfg.targetRefWhitelist().stream()
                .map(ref -> "\"" + ref + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String content = ""
                + "schema_version = " + cfg.schemaVersion() + "\n"
                + "project = " + q(cfg.project()) + "\n"
                + "auth_repo = " + qp(cfg.authRepo()) + "\n"
                + "clones_root = " + qp(cfg.clonesRoot()) + "\n"
                + "target_ref_whitelist = [" + whitelist + "]\n"
                + "gate_home = " + qp(cfg.gateHome()) + "\n"
                + "approvals_dir = " + qp(cfg.approvalsDir()) + "\n"
                + "db_path = " + qp(cfg.dbPath()) + "\n"
                + "blob_root = " + qp(cfg.blobRoot()) + "\n"
                + "audit_path = " + qp(cfg.auditPath()) + "\n"
                + "locks_dir = " + qp(cfg.locksDir()) + "\n"
                + "index_dir = " + qp(cfg.indexDir()) + "\n"
                + "gate_identity.name = " + q(cfg.gateIdentity().name()) + "\n"
                + "gate_identity.email = " + q(cfg.gateIdentity().email()) + "\n"
                // Emit every policy key the loader knows, so this smoke fixture fails closed at the
                // config layer if TomlGateConfigLoader's known-keys set drifts from the writer here.
                // engine_accept_degraded mirrors Policy.defaults() (false) — A11 uses the manual
                // engine, so the flag has no observable effect; it is here for completeness.
                + "policy.strictness = " + cfg.policy().strictness() + "\n"
                + "policy.require_coverage = " + cfg.policy().requireCoverage() + "\n"
                + "policy.max_diff_bytes = " + cfg.policy().maxDiffBytes() + "\n"
                + "policy.max_diff_lines = " + cfg.policy().maxDiffLines() + "\n"
                + "policy.engine_accept_degraded = " + cfg.policy().engineAcceptDegraded() + "\n";
        Files.writeString(toml, content, StandardCharsets.UTF_8);
        return toml;
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static String qp(Path p) {
        return "\"" + p.toAbsolutePath().normalize().toString().replace('\\', '/') + "\"";
    }

    /**
     * A live MCP server child process. Writes one JSON-RPC request per line to the child's stdin and
     * reads exactly one response line from its stdout. {@link #close()} shuts the child down cleanly
     * (EOF on stdin → read loop ends → Spring context closes) and force-kills it if it lingers.
     */
    private static final class ChildMcpServer implements AutoCloseable {

        private final Process process;
        private final Path logFile;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;
        private int nextId = 1;

        ChildMcpServer(Process process, Path logFile) {
            this.process = process;
            this.logFile = logFile;
            this.stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
        }

        /** Sends {@code initialize} and drains the capabilities response. */
        void initialize() {
            String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++)
                    + ",\"method\":\"initialize\",\"params\":{}}";
            send(line);
            String resp = readLine();
            if (resp == null || !resp.contains("protocolVersion")) {
                fail("child MCP server did not answer initialize (resp=" + resp + ")\n" + tailLog());
            }
        }

        /** Calls one tool and returns the raw JSON-RPC response line. */
        String call(String tool, String argsJson) {
            String line = "{\"jsonrpc\":\"2.0\",\"id\":" + (nextId++)
                    + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                    + "\",\"arguments\":" + argsJson + "}}";
            send(line);
            String resp = readLine();
            if (resp == null) {
                fail("child MCP server closed stdout before answering tool '" + tool + "'\n" + tailLog());
            }
            return resp;
        }

        private void send(String jsonLine) {
            try {
                stdin.write(jsonLine);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to write to child stdin\n" + tailLog(), e);
            }
        }

        /** Reads the next non-blank line from the child's stdout (blocks). */
        private String readLine() {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (!line.isBlank()) {
                        return line;
                    }
                }
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read from child stdout\n" + tailLog(), e);
            }
        }

        private String tailLog() {
            try {
                if (Files.isRegularFile(logFile)) {
                    return "--- child stderr (" + logFile + ") ---\n"
                            + Files.readString(logFile, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // best-effort diagnostics only
            }
            return "(no child log at " + logFile + ")";
        }

        @Override
        public void close() {
            try {
                stdin.close(); // EOF → server read loop ends → Spring context closes → System.exit
            } catch (IOException ignored) {
                // fall through to force-kill
            }
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(15, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try {
                stdout.close();
            } catch (IOException ignored) {
                // nothing left to do
            }
        }
    }
}
