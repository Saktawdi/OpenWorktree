package gate.adapters.session;

import gate.adapters.mcp.McpServer;
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
 * classpath, which already contains gate-cli's classes. The main class is referenced as a string
 * on purpose: gate-cli depends on gate-adapters, and a compile-time reference here would invert
 * the module graph.
 *
 * <p>The domain token travels via the {@code GATE_DOMAIN_TOKEN} environment variable, never argv
 * (§6.1 — argv is world-readable). It is minted per session, bound to one ticket, and its
 * plaintext lives only in the spawned process tree and the per-session config under the clone's
 * {@code .git/gate-context/} (inside {@code .git}, so it never shows up in the ticket's diff).
 */
public final class GateMcpProvisioning {

    /** Referenced by name — gate-cli is a downstream module of gate-adapters (see class doc). */
    private static final String GATE_APP_MAIN = "gate.cli.GateApp";

    private GateMcpProvisioning() {
    }

    /**
     * argv that launches {@code gate mcp serve} as a sibling of the current JVM.
     *
     * @param gateToml path passed as {@code --config}; the MCP child must resolve the same
     *                 database the web backend writes to, or token validation would read a
     *                 different credential store
     */
    public static List<String> serveArgv(Path gateToml) {
        List<String> argv = new ArrayList<>();
        argv.add(javaExecutable());
        argv.add("-cp");
        argv.add(System.getProperty("java.class.path"));
        argv.add(GATE_APP_MAIN);
        argv.add("mcp");
        argv.add("serve");
        argv.add("--config");
        argv.add(gateToml.toAbsolutePath().toString());
        return argv;
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
        sb.append("\"}}}");
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
        sb.append("\"}}}");
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
