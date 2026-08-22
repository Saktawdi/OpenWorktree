package gate.adapters.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal structured JSONL logger for the session adapters, appended to
 * {@code <gate-home>/adapters.log}. Adapter failures were previously invisible (silent catch
 * blocks everywhere), which made incident diagnosis depend entirely on opencode/claude's own
 * logs — this gives Gate a first-party trail of every spawn/stream/send/cleanup decision.
 *
 * <p>One JSON object per line: {"ts","component","level","event",...kv}. File appends are
 * synchronized and best-effort; logging must never break the operation it describes. Use
 * {@link #noop()} in tests.
 */
public final class AdapterLog {

    private final Path file;

    private AdapterLog(Path file) {
        this.file = file;
    }

    public static AdapterLog at(Path file) {
        if (file == null) {
            return noop();
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ignored) {
            return noop();
        }
        return new AdapterLog(file);
    }

    public static AdapterLog noop() {
        return new AdapterLog(null);
    }

    public boolean enabled() {
        return file != null;
    }

    /** kv is a flat key1, value1, key2, value2, ... sequence; values rendered via {@link #json}. */
    public synchronized void event(String component, String level, String event, Object... kv) {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("{\"ts\":\"").append(Instant.now()).append('"')
                .append(",\"component\":\"").append(escape(component)).append('"')
                .append(",\"level\":\"").append(escape(level)).append('"')
                .append(",\"event\":\"").append(escape(event)).append('"');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(',').append('"').append(escape(String.valueOf(kv[i]))).append("\":")
                    .append(json(kv[i + 1]));
        }
        sb.append('}');
        try {
            Files.writeString(file, sb + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // never let logging break the adapter
        }
    }

    public void info(String component, String event, Object... kv) {
        event(component, "INFO", event, kv);
    }

    public void warn(String component, String event, Object... kv) {
        event(component, "WARN", event, kv);
    }

    public void error(String component, String event, Object... kv) {
        event(component, "ERROR", event, kv);
    }

    @SuppressWarnings("unchecked")
    private static String json(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":")
                        .append(json(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Iterable<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                sb.append(json(item));
            }
            return sb.append(']').toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
