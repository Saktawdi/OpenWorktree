package gate.bootstrap;

import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.mcp.McpServer;
import gate.adapters.mcp.McpToolDispatcher;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone MCP stdio entrypoint for agent-session children:
 * {@code McpServeApp --config gate.toml} with the domain token in {@code GATE_DOMAIN_TOKEN}.
 * Functionally identical to {@code gate mcp serve} (gate-cli {@code McpCommand.Serve}) — keep the
 * dispatcher wiring below in sync with it.
 *
 * <p>Why a second entrypoint: {@link GateMcpProvisioning} rebuilds the MCP child command from the
 * RUNNING backend JVM's own classpath, and the entrypoint must therefore live on every classpath a
 * backend can be launched with. gate-bootstrap is exactly that module — gate-web and gate-cli both
 * depend on it — while gate-cli is NOT guaranteed present: gate-web must not depend on gate-cli
 * (ArchUnit §2.1), so an IDE-launched web backend carries no {@code gate.cli.GateApp} and every
 * session silently lost {@code presubmit_create}. No Spring here (unlike GateApp): the MCP child
 * has no DI needs and boots faster without the context.
 */
public final class McpServeApp {

    private McpServeApp() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            // The MCP client expects UTF-8 on stdio, but on Windows JDK 17 System.out wraps the
            // console charset (GBK on zh-CN) — tool results containing 中文 would leave the child
            // as GBK bytes and reach the agent as mojibake (the T-126 garbled title). The reader
            // side is already UTF-8 (McpServer); the writer side is forced to match here.
            PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            PrintStream utf8Err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
            exitCode = run(List.of(args), System.getenv(McpServer.TOKEN_ENV),
                    System.in, utf8Out, utf8Err);
        } catch (GateException e) {
            System.err.println("gate-mcp: " + e.getMessage());
            exitCode = e.code().code();
        } catch (Exception e) {
            System.err.println("gate-mcp: internal error: " + e);
            exitCode = 70;
        }
        System.out.flush();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Parses argv, validates the domain token and serves MCP over stdio until EOF.
     *
     * @param token the agent-domain token ({@code GATE_DOMAIN_TOKEN} value); must be non-blank
     * @return process exit code — 0 when the serve loop ended cleanly (stdin EOF)
     */
    public static int run(List<String> args, String token, InputStream in, PrintStream out, PrintStream err) {
        Path configPath = parseConfigPath(args);
        if (token == null || token.isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "GATE_DOMAIN_TOKEN environment variable is not set; the MCP server requires "
                            + "a domain token (never passed as argv, §6.1)");
        }
        serve(configPath, token, in, out, err);
        return 0;
    }

    static void serve(Path configPath, String token, InputStream in, PrintStream out, PrintStream err) {
        GateConfig config = new TomlGateConfigLoader().load(configPath);
        GateRuntime runtime = new GateRuntime(config, "git");
        runtime.seedManualProvider();
        McpToolDispatcher dispatcher = new McpToolDispatcher(
                runtime.gateService(), runtime.credentials(),
                runtime.presubmitRepository(), runtime.reviewResultRepository(),
                runtime.blobStore(), runtime.providerRepository(), runtime.config(),
                runtime.ticketRepository());
        new McpServer(dispatcher, token).run(in, out, err);
    }

    private static Path parseConfigPath(List<String> args) {
        String value = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--config".equals(arg) || "-c".equals(arg)) {
                if (i + 1 >= args.size()) {
                    throw new GateException(GateErrorCode.USAGE, "--config requires a path to gate.toml");
                }
                value = args.get(++i);
            } else if (arg.startsWith("--config=")) {
                value = arg.substring("--config=".length());
            } else {
                throw new GateException(GateErrorCode.USAGE,
                        "unknown argument: " + arg + " (usage: McpServeApp --config gate.toml)");
            }
        }
        if (value == null || value.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "--config <gate.toml> is required");
        }
        return Path.of(value);
    }
}
