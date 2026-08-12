package gate.adapters.engine;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a {@code .env} file into a plain {@code Map<String,String>}.
 *
 * <p>{@code .env} holds LLM provider secrets that must never reach the DB, argv, the audit log or a
 * commit trailer (ADR-9, §6.1, §10.1.1). It is git-ignored at the repo root. This reader is the only
 * code that opens it; the values it returns are injected into the prism child process environment
 * and nowhere else.
 *
 * <p>The format is the common dotenv subset: one {@code KEY=VALUE} per line, {@code #} comments, and
 * optional surrounding double quotes on the value. There is no shell expansion — the value is taken
 * literally, which is exactly what an API key needs.
 */
public final class EnvFile {

    private EnvFile() {
    }

    /**
     * @param envFile absolute path to {@code .env}
     * @return the parsed key/value pairs (empty if the file is absent)
     */
    public static Map<String, String> load(Path envFile) {
        Map<String, String> out = new LinkedHashMap<>();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return out;
        }
        String text;
        try {
            text = Files.readString(envFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "cannot read .env at " + envFile + ": " + e.getMessage(), e);
        }
        for (String rawLine : text.split("\n", -1)) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue; // be lenient on shape; a key without a value is simply not a binding
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    /** Strips a {@code #} comment that is not inside a quoted string. */
    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
