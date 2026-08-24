package gate.cli.util;


import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal JSON emitter for the stdout machine channel (§8.3: JSON to stdout, human text to stderr).
 *
 * <p>Every payload carries {@code schema_version}. stdout is flushed before the process exits so
 * Spring's {@code System.exit} cannot truncate it.
 */
public final class JsonOut {

    public static final int SCHEMA_VERSION = 1;

    private JsonOut() {
    }

    public static void emit(PrintStream out, String command, Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schema_version\":").append(SCHEMA_VERSION);
        sb.append(",\"command\":").append(quote(command));
        Map<String, ?> sorted = new TreeMap<>(fields);
        for (Map.Entry<String, ?> e : sorted.entrySet()) {
            sb.append(',').append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        sb.append('}');
        out.println(sb);
        out.flush();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(value(o));
            }
            return sb.append(']').toString();
        }
        return quote(v.toString());
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
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
        return sb.append('"').toString();
    }
}
