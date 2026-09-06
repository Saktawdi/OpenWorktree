package gate.adapters.mcp;

import gate.adapters.io.AdapterLog;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.error.GateValidationException;
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
 * (spike-結論 §3.3, 架构落地执行文档 §5.4).
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
 *
 * <p><b>Failure observability (T-108):</b> every failed {@code tools/call} is recorded through the
 * optional {@link AdapterLog} ({@code <gate-home>/adapters.log}, component {@value #LOG_COMPONENT})
 * with the tool name, request-argument summary and failure reason, so an agent-side failure is
 * traceable in the gate's own logs without grepping source or the DB. Error responses also carry a
 * structured {@code error.data}: {@code layer} distinguishes {@code validation} (fixable request
 * problems, with a {@code fields[]} list naming each broken field) from {@code domain} /
 * {@code permission} / {@code io} / {@code config} / {@code engine} / {@code internal} failures.
 */
public final class McpServer {

    private static final String SERVER_NAME = "gate-mcp";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** Environment variable carrying the domain token (never passed as argv, §6.1). */
    public static final String TOKEN_ENV = "GATE_DOMAIN_TOKEN";

    /** AdapterLog component name for MCP call records in adapters.log. */
    public static final String LOG_COMPONENT = "gate-mcp";

    private static final int MAX_LOG_VALUE = 400;

    private final McpToolDispatcher dispatcher;
    private final String domainToken; // from env var GATE_DOMAIN_TOKEN
    private final AdapterLog callLog;

    public McpServer(McpToolDispatcher dispatcher, String domainToken) {
        this(dispatcher, domainToken, AdapterLog.noop());
    }

    /** @param callLog best-effort JSONL failure/success trail; {@code AdapterLog.at(adapters.log)} in prod */
    public McpServer(McpToolDispatcher dispatcher, String domainToken, AdapterLog callLog) {
        this.dispatcher = dispatcher;
        this.domainToken = domainToken;
        this.callLog = callLog == null ? AdapterLog.noop() : callLog;
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
            String reason = "parse error: " + e.getMessage();
            err.println("gate-mcp: " + reason);
            out.println(McpJsonRpc.errorResponse(null, McpJsonRpc.PARSE_ERROR, reason, null));
            out.flush();
            logFailure(null, "protocol", null, McpJsonRpc.PARSE_ERROR, null, reason, null);
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

        String tool = "tools/call".equals(req.method) ? toolNameOf(req) : null;
        Map<String, Object> toolArgs = "tools/call".equals(req.method) ? toolArgsOf(req) : Map.of();
        try {
            Map<String, Object> result = handleMethod(req);
            out.println(McpJsonRpc.okResponse(req.id, result));
            if (tool != null) {
                callLog.info(LOG_COMPONENT, "mcp.tool_ok", "tool", tool, "arguments", summary(toolArgs));
            }
        } catch (McpToolDispatcher.PermissionDeniedException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("layer", "permission");
            data.put("tool", e.toolName);
            data.put("required_domain", e.requiredDomain);
            data.put("actual_domain", e.actualDomain);
            String reason = e.getMessage();
            err.println("gate-mcp: permission denied: " + reason);
            out.println(McpJsonRpc.errorResponse(req.id, McpJsonRpc.PERMISSION_DENIED, reason, data));
            logFailure(e.toolName, "permission", data, McpJsonRpc.PERMISSION_DENIED, null, reason, toolArgs);
        } catch (McpToolDispatcher.ToolException e) {
            String reason = e.getMessage();
            err.println("gate-mcp: tool error [" + req.method + "]: " + reason);
            out.println(McpJsonRpc.errorResponse(req.id, e.rpcCode, reason, e.data()));
            String layer = e.data() == null
                    ? (e.rpcCode == McpJsonRpc.INVALID_PARAMS ? "validation" : "protocol")
                    : String.valueOf(e.data().get("layer"));
            logFailure(tool, layer, e.data(), e.rpcCode, null, reason, toolArgs);
        } catch (GateValidationException e) {
            // Parameter-level validation: message and data both name the broken fields (-32602).
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("layer", "validation");
            data.put("tool", tool);
            data.put("error_code", e.code().name());
            data.put("fields", e.fieldData());
            String reason = e.getMessage();
            err.println("gate-mcp: validation error [" + req.method + "]: " + reason);
            out.println(McpJsonRpc.errorResponse(req.id, McpJsonRpc.INVALID_PARAMS, reason, data));
            logFailure(tool, "validation", data, McpJsonRpc.INVALID_PARAMS,
                    e.code().name(), reason, toolArgs);
        } catch (GateException e) {
            // Domain/state failures (USAGE, REJECT_*) map to INVALID_PARAMS with layer=domain;
            // infrastructure failures (GATE_ERROR_*) stay internal errors with their own layer.
            boolean infra = e.code().code() >= 20 && e.code() != GateErrorCode.USAGE;
            int rpcCode = infra ? McpJsonRpc.INTERNAL_ERROR : McpJsonRpc.INVALID_PARAMS;
            String layer = switch (e.code()) {
                case GATE_ERROR_IO -> "io";
                case GATE_ERROR_CONFIG -> "config";
                case GATE_ERROR_ENGINE -> "engine";
                case INTERNAL -> "internal";
                default -> "domain";
            };
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("layer", layer);
            data.put("tool", tool);
            data.put("error_code", e.code().name());
            String reason = e.getMessage();
            err.println("gate-mcp: gate error [" + req.method + "]: " + reason);
            out.println(McpJsonRpc.errorResponse(req.id, rpcCode, reason, data));
            logFailure(tool, layer, data, rpcCode, e.code().name(), reason, toolArgs);
        } catch (Exception e) {
            String reason = "internal error: " + e;
            err.println("gate-mcp: internal error [" + req.method + "]: " + reason);
            out.println(McpJsonRpc.errorResponse(req.id, McpJsonRpc.INTERNAL_ERROR, reason, null));
            logFailure(tool, "internal", null, McpJsonRpc.INTERNAL_ERROR, null, reason, toolArgs);
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
                    "method not found: " + req.method,
                    Map.of("layer", "protocol", "method", req.method));
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
                    "tools/call requires 'name' parameter",
                    Map.of("layer", "validation", "tool", "tools/call"));
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

    // --- tool-call identification for logging (never the token; token arrives via env only) ---

    @SuppressWarnings("unchecked")
    private static String toolNameOf(McpJsonRpc.Request req) {
        Map<String, Object> params = req.params;
        return params != null && params.get("name") instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolArgsOf(McpJsonRpc.Request req) {
        Map<String, Object> params = req.params;
        if (params != null && params.get("arguments") instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * Records one failed tool call in the gate's own log (T-108): request summary + reason, so a
     * failure the agent saw can be audited without flipping through source. Logging is best-effort
     * and must never break the operation it describes.
     */
    private void logFailure(String tool, String layer, Map<String, Object> data, int rpcCode,
                            String errorCode, String reason, Map<String, Object> args) {
        if (!callLog.enabled()) {
            return;
        }
        String level = switch (layer) {
            case "io", "config", "engine", "internal" -> "ERROR";
            default -> "WARN";
        };
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("tool", tool);
        kv.put("layer", layer);
        kv.put("rpc_code", (long) rpcCode);
        kv.put("error_code", errorCode);
        kv.put("reason", truncate(reason, MAX_LOG_VALUE));
        if (data != null) {
            kv.put("data", truncateData(data));
        }
        if (tool != null) {
            kv.put("arguments", summary(args));
        }
        Object[] flat = new Object[kv.size() * 2];
        int i = 0;
        for (Map.Entry<String, Object> entry : kv.entrySet()) {
            flat[i++] = entry.getKey();
            flat[i++] = entry.getValue();
        }
        callLog.event(LOG_COMPONENT, level, "mcp.tool_failed", flat);
    }

    /** Flat summary of the request arguments, truncated — never the token (it is not an argument). */
    private static String summary(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(truncate(String.valueOf(e.getValue()), 120));
        }
        return truncate(sb.append('}').toString(), MAX_LOG_VALUE);
    }

    /** Truncates nested structured data (the error payload) for the log line. */
    private static String truncateData(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(truncate(String.valueOf(e.getValue()), 160));
        }
        return truncate(sb.append('}').toString(), MAX_LOG_VALUE);
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max - 3) + "...";
    }
}
