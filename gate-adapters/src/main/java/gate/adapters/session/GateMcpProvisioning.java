package gate.adapters.session;

import gate.adapters.mcp.McpServer;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Provisions the gate's own MCP tools ({@code presubmit_create} et al.) to agent CLI processes.
 *
 * <p>The MCP server ships as the {@code gate mcp serve} stdio command — but this deployment has no
 * {@code gate} executable on PATH: the backend itself runs from {@code target/classes} via
 * {@code java -cp} (see {@code start-local.bat}). A config pointing at {@code command: "gate"} can
 * therefore never spawn; the child command is rebuilt from the RUNNING JVM's own java home and
 * classpath. The entrypoint is chosen by resolvability on that classpath: {@code
 * gate.bootstrap.McpServeApp} first (gate-bootstrap rides every backend classpath — gate-web and
 * gate-cli both depend on it), then the legacy {@code gate.cli.GateApp mcp serve}. When neither is
 * loadable the provisioning REFUSES loudly: a written-but-unstartable config once cost every
 * session its {@code presubmit_create} (an IDE-launched web backend carries no gate-cli, so the
 * child died with ClassNotFoundException before the agent ever saw the tool). The main class is
 * referenced as a string on purpose: both entrypoints sit downstream of gate-adapters, and a
 * compile-time reference here would invert the module graph.
 *
 * <p>The domain token travels via the {@code GATE_DOMAIN_TOKEN} environment variable, never argv
 * (§6.1 — argv is world-readable). It is minted per session, bound to one ticket, and its
 * plaintext lives only in the spawned process tree and the per-session config under the clone's
 * {@code .git/gate-context/} (inside {@code .git}, so it never shows up in the ticket's diff).
 */
public final class GateMcpProvisioning {

    /**
     * Preferred child entrypoint: gate-bootstrap is on every backend classpath (gate-web and
     * gate-cli both depend on it), so it survives IDE launches, {@code java @args} files and
     * start-local.bat alike.
     */
    public static final String BOOTSTRAP_SERVE_MAIN = "gate.bootstrap.McpServeApp";

    /** Legacy child entrypoint ({@code gate mcp serve}); only loadable when gate-cli is on the classpath. */
    public static final String GATE_APP_MAIN = "gate.cli.GateApp";

    private GateMcpProvisioning() {
    }

    /**
     * argv that launches the gate MCP stdio server as a sibling of the current JVM.
     *
     * @param gateToml path passed as {@code --config}; the MCP child must resolve the same
     *                 database the web backend writes to, or token validation would read a
     *                 different credential store
     * @throws GateException when no entrypoint is loadable from this JVM's classpath — failing
     *                       the session loudly beats starting it without the gate tools
     */
    public static List<String> serveArgv(Path gateToml) {
        return serveArgv(gateToml, System.getProperty("java.class.path", ""),
                GateMcpProvisioning::resolvable);
    }

    /** Test seam: explicit classpath text and entrypoint resolvability probe. */
    static List<String> serveArgv(Path gateToml, String classpath, java.util.function.Predicate<String> resolvable) {
        String cp = absoluteClasspath(classpath);
        if (resolvable.test(BOOTSTRAP_SERVE_MAIN)) {
            return argv(cp, BOOTSTRAP_SERVE_MAIN, List.of(), gateToml);
        }
        if (resolvable.test(GATE_APP_MAIN)) {
            return argv(cp, GATE_APP_MAIN, List.of("mcp", "serve"), gateToml);
        }
        throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                "no MCP entrypoint on the backend classpath: neither " + BOOTSTRAP_SERVE_MAIN
                        + " nor " + GATE_APP_MAIN + " is loadable, so the per-session MCP server "
                        + "(presubmit_create et al.) could never start. Launch the backend with "
                        + "gate-bootstrap on the classpath (gate-web, gate-cli and start-local.bat "
                        + "all carry it) or add gate-cli/target/classes.");
    }

    private static List<String> argv(String classpath, String mainClass, List<String> verb, Path gateToml) {
        List<String> argv = new ArrayList<>();
        argv.add(javaExecutable());
        argv.add("-cp");
        argv.add(classpath);
        argv.add(mainClass);
        argv.addAll(verb);
        argv.add("--config");
        argv.add(gateToml.toAbsolutePath().toString());
        return argv;
    }

    /**
     * Re-anchors every classpath entry to an absolute path. The MCP child runs with the ticket
     * clone as cwd, but a backend launched via {@code java @args} or an IDE may carry RELATIVE
     * classpath entries that only resolve from the backend's own working directory — without this,
     * the child would not find classes the parent clearly has.
     */
    static String absoluteClasspath(String classpath) {
        StringBuilder sb = new StringBuilder();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (sb.length() > 0) {
                sb.append(java.io.File.pathSeparator);
            }
            // An empty entry means the JVM default dir; Path.of("").toAbsolutePath() resolves it.
            sb.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return sb.toString();
    }

    /** Faithful probe of what a child spawned with THIS JVM's classpath could load. */
    static boolean resolvable(String mainClass) {
        try {
            Class.forName(mainClass, false, GateMcpProvisioning.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * opencode config content (the {@code mcp} block). opencode merges {@code OPENCODE_CONFIG}
     * with the user's global config key-by-key (docs: "Configuration files are merged together,
     * not replaced"), so the agent's global providers/models survive while the gate server is
     * added. opencode's local-server schema wants the whole command line as ONE array and env
     * vars under {@code environment} — not Claude's command/args/env split.
     */
    public static String opencodeConfigJson(List<String> serveArgv, String token) {
        StringBuilder sb = new StringBuilder("{\"mcp\":{\"gate\":{\"type\":\"local\",\"command\":");
        appendStringArray(sb, serveArgv);
        sb.append(",\"enabled\":true,\"environment\":{\"").append(McpServer.TOKEN_ENV).append("\":\"");
        escapeInto(sb, token);
        // four opens (root/mcp/gate/environment) need four closes — a missing brace makes opencode
        // reject OPENCODE_CONFIG with ConfigJsonError CloseBraceExpected and /session returns 503.
        sb.append("\"}}}}");
        return sb.toString();
    }

    /**
     * Claude {@code --mcp-config} content (the {@code mcpServers} block): command as a single
     * string plus a separate {@code args} array and an {@code env} map.
     */
    public static String claudeConfigJson(List<String> serveArgv, String token) {
        StringBuilder sb = new StringBuilder("{\"mcpServers\":{\"gate\":{\"command\":\"");
        escapeInto(sb, serveArgv.get(0));
        sb.append("\",\"args\":");
        appendStringArray(sb, serveArgv.subList(1, serveArgv.size()));
        sb.append(",\"env\":{\"").append(McpServer.TOKEN_ENV).append("\":\"");
        escapeInto(sb, token);
        // four opens (root/mcpServers/gate/env) need four closes — see opencodeConfigJson.
        sb.append("\"}}}}");
        return sb.toString();
    }

    /** Absolute java executable: opencode/claude spawn command[0] directly, so no PATH lookup. */
    private static String javaExecutable() {
        String bin = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", bin).toString();
    }

    private static void appendStringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"');
            escapeInto(sb, values.get(i));
            sb.append('"');
        }
        sb.append(']');
    }

    private static void escapeInto(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
