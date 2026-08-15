package gate.web;

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer for the web response channel (执行文档-后端-web §4.4).
 *
 * <p>gate-web deliberately avoids Jackson (ADR-8 spirit: no gratuitous frameworks). This handles the
 * exact value shapes the handlers emit: maps, lists, strings, numbers, booleans, null. Keys are
 * emitted in insertion order (callers use {@link java.util.LinkedHashMap}).
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, m);
        } else if (v instanceof Iterable<?> it) {
            writeArray(sb, it);
        } else {
            quote(sb, v.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void quote(StringBuilder sb, String raw) {
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

    /** Convenience for the standard error envelope (执行文档-后端-web §4.4). */
    static String error(int errorCode, String errorName, String message, List<String> detail) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error_code", errorCode);
        body.put("error", errorName);
        body.put("message", message == null ? "" : message);
        body.put("detail", detail == null ? List.of() : detail);
        return write(body);
    }
}
