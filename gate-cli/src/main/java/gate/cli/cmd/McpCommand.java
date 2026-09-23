package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.adapters.mcp.McpServer;
import gate.adapters.mcp.McpToolDispatcher;
import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.PrintStream;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate mcp serve|issue-token}: MCP stdio server and credential management (P3).
 *
 * <p>{@code gate mcp serve} starts the MCP stdio server — a driver adapter over {@link
 * gate.application.GateService} (§5.4: no GateTransport port). The domain token is read from the
 * {@code GATE_DOMAIN_TOKEN} environment variable (never argv, §6.1).
 *
 * <p>{@code gate mcp issue-token --ticket T} issues an agent-domain token (bound to one ticket).
 * {@code gate mcp issue-token --human} issues a human-domain token. The plaintext token is printed
 * once to stdout and never stored — only its SHA-256 hash is persisted (ADR-9).
 */
@CommandLine.Command(name = "mcp",
        description = "MCP stdio server and two-domain credential management (P3)",
        subcommands = {
                McpCommand.Serve.class,
                McpCommand.IssueToken.class
        })
public final class McpCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    /** {@code gate mcp serve}: start the MCP stdio server on stdin/stdout. */
    @CommandLine.Command(name = "serve", description = "Start the MCP stdio server")
    static final class Serve extends BaseCommand {

        @Override
        public void run() {
            GateComponents c = components();
            String domainToken = System.getenv(McpServer.TOKEN_ENV);
            if (domainToken == null || domainToken.isBlank()) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "GATE_DOMAIN_TOKEN environment variable is not set; the MCP server requires "
                                + "a domain token (never passed as argv, §6.1)");
            }
            McpToolDispatcher dispatcher = new McpToolDispatcher(
                    c.gateService(), c.credentials(),
                    c.presubmitRepository(), c.reviewResultRepository(),
                    c.blobStore(), c.providerRepository(), c.config(),
                    c.ticketRepository(), c.sessionRepository());
            // Failed MCP calls land in adapters.log (component gate-mcp) — same trail the backend
            // writes, so agent-side failures stay auditable (T-108).
            gate.adapters.io.AdapterLog callLog = gate.adapters.io.AdapterLog.at(
                    c.config().gateHome().resolve("adapters.log"));
            McpServer server = new McpServer(dispatcher, domainToken, callLog);
            // 与 McpServeApp 同理：stdio 上的 MCP 客户端期望 UTF-8，Windows 控制台默认
            // 字符集（GBK）会把工具结果里的中文写成乱码；写入侧强制 UTF-8。
            PrintStream utf8Out = new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8);
            PrintStream utf8Err = new PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8);
            server.run(System.in, utf8Out, utf8Err);
        }
    }

    /**
     * {@code gate mcp issue-token --ticket T | --human}: issue a domain token.
     * The plaintext is printed once; only the hash is persisted.
     */
    @CommandLine.Command(name = "issue-token", description = "Issue an agent or human domain token")
    static final class IssueToken extends BaseCommand {

        @CommandLine.Option(names = "--ticket", description = "Issue an agent-domain token bound to this ticket")
        String ticketNo;

        @CommandLine.Option(names = "--human", description = "Issue a human-domain token")
        boolean human;

        @Override
        public void run() {
            if (ticketNo == null && !human) {
                throw new GateException(GateErrorCode.USAGE,
                        "specify --ticket <T> for an agent-domain token or --human for a human-domain token");
            }
            GateComponents c = components();
            String token;
            String domain;
            if (human) {
                token = c.credentials().issueHumanToken(c.clock().now());
                domain = "HUMAN";
            } else {
                token = c.credentials().issueAgentToken(ticketNo, c.clock().now());
                domain = "AGENT";
            }
            JsonOut.emit(System.out, "mcp.issue_token", Map.of(
                    "domain", domain,
                    "ticket_no", ticketNo == null ? "" : ticketNo,
                    "token", token,
                    "note", "plaintext shown once; only the SHA-256 hash is stored"));
        }
    }
}
