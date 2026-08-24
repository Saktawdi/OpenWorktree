package gate.adapters.mcp;

import gate.application.util.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 message handling for the MCP stdio server.
 *
 * <p>Parses incoming requests with {@link MiniJson} (the application-layer dependency-free parser)
 * and serialises responses with a minimal inline emitter — the same approach {@code JsonOut} uses in
 * the CLI adapter. No external JSON library is needed.
 *
 * <p>Each line on stdio is one JSON-RPC message. Notifications (requests without {@code id}) get no
 * response. Requests get a {@code result} or an {@code error} object.
 */
final class McpJsonRpc {

    /** JSON-RPC error codes (https://www.jsonrpc.org/specification#error_object). */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int PERMISSION_DENIED = -32603; // re-use internal error range; message distinguishes

    private McpJsonRpc() {
    }

    /** A parsed JSON-RPC request or notification. */
    static final class Request {
        final String jsonrpc;
        final Object id;          // may be null for notifications, or Long/String
        final String method;
        final Map<String, Object> params;

        Request(String jsonrpc, Object id, String method, Map<String, Object> params) {
            this.jsonrpc = jsonrpc;
            this.id = id;
            this.method = method;
            this.params = params;
        }

        boolean isNotification() {
            return id == null;
        }
    }

    /** Parse one line of stdin into a Request, or null if the line is empty/whitespace. */
    @SuppressWarnings("unchecked")
    static Request parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Object parsed = MiniJson.parse(line);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("JSON-RPC message must be an object");
        }
        Map<String, Object> obj = (Map<String, Object>) m;
        String jsonrpc = strOrNull(obj.get("jsonrpc"));
        Object id = obj.get("id"); // may be Long, String, or null (notification)
        String method = strOrNull(obj.get("method"));
        Map<String, Object> params = obj.get("params") instanceof Map<?, ?> pm
                ? (Map<String, Object>) pm : Map.of();
        return new Request(jsonrpc, id, method, params);
    }

    /** Build a successful JSON-RPC response string. */
    static String okResponse(Object id, Map<String, Object> result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", result);
        return serialize(envelope);
    }

    /** Build a JSON-RPC error response string. */
    static String errorResponse(Object id, int code, String message, Object data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", (long) code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("error", error);
        return serialize(envelope);
    }

    // --- minimal JSON serializer (handles Map, List, String, Number, Boolean, null) ---

    static String serialize(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else {
            // Fall back to string representation for any other type
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
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
        sb.append('"');
    }

    private static String strOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
