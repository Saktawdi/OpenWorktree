package gate.adapters.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP stdio server: reads JSON-RPC 2.0 messages from stdin, writes responses to stdout
 * (spike-结论 §3.3, 架构落地执行文档 §5.4).
 *
 * <p>The server is spawned by the orchestrator per-ticket and communicates exclusively over stdio.
 * It opens no listening port — the attack surface is confined to the spawn moment (§10.4 / N7).
 * The domain token arrives via the {@code GATE_DOMAIN_TOKEN} environment variable, never argv
 * (§6.1).
 *
 * <p>Supported methods:
 * <ul>
 *   <li>{@code initialize} — returns server capabilities</li>
 *   <li>{@code notifications/initialized} — acknowledged silently (no response)</li>
 *   <li>{@code tools/list} — returns all registered tools with their schemas</li>
 *   <li>{@code tools/call} — dispatches to {@link McpToolDispatcher} after domain check</li>
 * </ul>
 *
 * <p>Any exception during message handling produces a JSON-RPC error response rather than crashing
 * the server. Malformed lines are logged to stderr and skipped.
 */
public final class McpServer {

    private static final String SERVER_NAME = "gate-mcp";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** Environment variable carrying the domain token (never passed as argv, §6.1). */
    public static final String TOKEN_ENV = "GATE_DOMAIN_TOKEN";

    private final McpToolDispatcher dispatcher;
    private final String domainToken; // from env var GATE_DOMAIN_TOKEN

    public McpServer(McpToolDispatcher dispatcher, String domainToken) {
        this.dispatcher = dispatcher;
        this.domainToken = domainToken;
    }

    /**
     * Runs the stdio read loop until EOF. Each line is one JSON-RPC message.
     *
     * @param in  stdin (or a test pipe)
     * @param out stdout (or a test pipe)
     * @param err stderr for diagnostics (never JSON)
     */
    public void run(InputStream in, PrintStream out, PrintStream err) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line, out, err);
            }
        } catch (IOException e) {
            err.println("gate-mcp: stdin read error: " + e.getMessage());
        } finally {
            out.flush();
        }
    }

    @SuppressWarnings("unchecked")
    private void processLine(String line, PrintStream out, PrintStream err) {
        if (line.isBlank()) {
            return;
        }

        McpJsonRpc.Request req;
        try {
            req = McpJsonRpc.parse(line);
        } catch (Exception e) {
            // Parse error — we don't know the id, so use null.
            err.println("gate-mcp: parse error: " + e.getMessage());
            out.println(McpJsonRpc.errorResponse(null, McpJsonRpc.PARSE_ERROR,
                    "parse error: " + e.getMessage(), null));
            out.flush();
            return;
        }

        if (req == null) {
            return;
        }

        // Notifications get no response.
        if (req.isNotification()) {
            if ("notifications/initialized".equals(req.method)) {
                err.println("gate-mcp: client initialized");
            }
            return;
        }

        try {
            Map<String, Object> result = handleMethod(req);
            out.println(McpJsonRpc.okResponse(req.id, result));
        } catch (McpToolDispatcher.PermissionDeniedException e) {
            err.println("gate-mcp: permission denied: " + e.getMessage());
            out.println(McpJsonRpc.errorResponse(req.id, McpJsonRpc.PERMISSION_DENIED,
                    e.getMessage(), null));
        } catch (McpToolDispatcher.ToolException e) {
            err.println("gate-mcp: tool error [" + req.method + "]: " + e.getMessage());
            out.println(McpJsonRpc.errorResponse(req.id, e.rpcCode, e.getMessage(), null));
        } catch (gate.domain.error.GateException e) {
            // Map GateException to a JSON-RPC error. The exit code table (§8.3) maps to RPC codes:
            // REJECT_* → INVALID_PARAMS (the caller can inspect the message), GATE_ERROR_* → INTERNAL.
            int rpcCode = e.code().code() >= 20 ? McpJsonRpc.INTERNAL_ERROR : McpJsonRpc.INVALID_PARAMS;
            err.println("gate-mcp: gate error [" + req.method + "]: " + e.getMessage());
            out.println(McpJsonRpc.errorResponse(req.id, rpcCode, e.getMessage(), null));
        } catch (Exception e) {
            err.println("gate-mcp: internal error [" + req.method + "]: " + e.getMessage());
            out.println(McpJsonRpc.errorResponse(req.id, McpJsonRpc.INTERNAL_ERROR,
                    "internal error: " + e.getMessage(), null));
        }
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleMethod(McpJsonRpc.Request req) {
        return switch (req.method) {
            case "initialize" -> handleInitialize();
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(req.params);
            default -> throw new McpToolDispatcher.ToolException(McpJsonRpc.METHOD_NOT_FOUND,
                    "method not found: " + req.method);
        };
    }

    private Map<String, Object> handleInitialize() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", "0.3.0");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> handleToolsList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpToolRegistry.ToolDef def : McpToolRegistry.all()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", def.name());
            tool.put("description", def.description());
            tool.put("inputSchema", def.inputSchema());
            tools.add(tool);
        }
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params) {
        String name = params.get("name") instanceof String s ? s : null;
        if (name == null) {
            throw new McpToolDispatcher.ToolException(McpJsonRpc.INVALID_PARAMS,
                    "tools/call requires 'name' parameter");
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        Map<String, Object> resultData = dispatcher.dispatch(name, arguments, domainToken);

        // MCP tools/call result format: { content: [{ type: "text", text: "..." }] }
        String textContent = McpJsonRpc.serialize(resultData);
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", textContent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(contentItem));
        return result;
    }
}