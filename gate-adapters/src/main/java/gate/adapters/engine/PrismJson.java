package gate.adapters.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal self-contained JSON parser for engine output: the review findings schema
 * ({@link #parse}) and generic objects such as OpenAI-compatible SSE chunks ({@link #parseObjectMap}).
 *
 * <p>This is deliberately self-contained rather than reusing {@code gate.application.util.MiniJson}: that
 * class is package-private to the application layer, the adapter must not reach across, and the
 * fail-closed contract wants a boundary where any structural problem becomes an
 * {@link gate.domain.review.EngineFailure}({@code UNPARSEABLE}). Anything thrown here is caught by
 * the engine adapters' {@code review} and turned into a value — it never propagates as an exception.
 *
 * <p>Handles objects, arrays, strings (with standard escapes), integers, floating-point, booleans and
 * null. Floating-point is parsed as {@code double} (prism's {@code confidence} field) and then
 * ignored by the mapping; it is accepted only so the parser does not reject a well-formed document.
 */
final class PrismJson {

    private PrismJson() {
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || num.equals("-")) {
                // 非数字开头的垃圾（如 thinking 模型混入的 <think>）在此零消费：必须指认字符与位置，
                // 否则 Long.parseLong("") 只会给出无从定位的 "For input string: \"\""。
                throw new IllegalArgumentException("expected number but got '" + peek() + "' at " + i);
            }
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad boolean at " + i);
        }

        Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw new IllegalArgumentException("bad null at " + i);
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return s.charAt(i);
        }

        char next() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return s.charAt(i++);
        }

        void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new IllegalArgumentException("expected '" + c + "' but got '" + actual + "' at " + (i - 1));
            }
        }
    }

    /** Parsed prism output, with only the fields the mapping consumes. */
    static final class PrismOutput {
        final List<PrismFinding> findings;
        final String version;
        final Long totalMs;  // timing.totalMs (P4 cost telemetry)
        final Long llmMs;    // timing.llmMs (P4 cost telemetry)

        PrismOutput(List<PrismFinding> findings, String version, Long totalMs, Long llmMs) {
            this.findings = findings;
            this.version = version;
            this.totalMs = totalMs;
            this.llmMs = llmMs;
        }
    }

    static final class PrismFinding {
        final String id;
        final String severity;
        final String title;
        final String message;
        final String suggestion;
        final List<PrismLocation> locations;

        PrismFinding(String id, String severity, String title, String message, String suggestion,
                     List<PrismLocation> locations) {
            this.id = id;
            this.severity = severity;
            this.title = title;
            this.message = message;
            this.suggestion = suggestion;
            this.locations = locations;
        }
    }

    static final class PrismLocation {
        final String path;
        final PrismLines lines;

        PrismLocation(String path, PrismLines lines) {
            this.path = path;
            this.lines = lines;
        }
    }

    static final class PrismLines {
        final Integer start;
        final Integer end;

        PrismLines(Integer start, Integer end) {
            this.start = start;
            this.end = end;
        }
    }

    @SuppressWarnings("unchecked")
    static PrismOutput parse(String json) {
        Parser p = new Parser(json);
        Object root = p.parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("prism output root is not an object");
        }
        Map<String, Object> obj = (Map<String, Object>) root;
        List<PrismFinding> findings = new ArrayList<>();
        Object findingsRaw = obj.get("findings");
        if (findingsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                findings.add(parseFinding((Map<String, Object>) item));
            }
        }
        Object versionRaw = obj.get("version");
        String version = versionRaw == null ? null : String.valueOf(versionRaw);
        Long totalMs = extractTimingMs(obj, "totalMs");
        Long llmMs = extractTimingMs(obj, "llmMs");
        return new PrismOutput(findings, version, totalMs, llmMs);
    }

    /**
     * 解析任意合法的顶层 JSON 对象（OpenAI 兼容网关的 SSE chunk 就是一种），不绑定 findings schema。
     * 结构不符时抛 IllegalArgumentException —— SSE 消费层对坏帧按噪声容错跳过。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObjectMap(String json) {
        Parser p = new Parser(json);
        Object root = p.parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("JSON root is not an object");
        }
        return (Map<String, Object>) root;
    }

    /**
     * Extracts a millisecond field from prism's {@code timing} object (P4 cost telemetry).
     * Returns null if timing or the field is absent — the caller treats null as "unavailable".
     */
    @SuppressWarnings("unchecked")
    private static Long extractTimingMs(Map<String, Object> obj, String key) {
        Object timingRaw = obj.get("timing");
        if (!(timingRaw instanceof Map<?, ?>)) {
            return null;
        }
        Object val = ((Map<String, Object>) timingRaw).get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static PrismFinding parseFinding(Map<String, Object> m) {
        String id = stringOrNull(m.get("id"));
        String severity = stringOrNull(m.get("severity"));
        String title = stringOrNull(m.get("title"));
        String message = stringOrNull(m.get("message"));
        String suggestion = stringOrNull(m.get("suggestion"));
        List<PrismLocation> locations = new ArrayList<>();
        Object locs = m.get("locations");
        if (locs instanceof List<?> list) {
            for (Object loc : list) {
                if (!(loc instanceof Map)) {
                    continue;
                }
                locations.add(parseLocation((Map<String, Object>) loc));
            }
        }
        return new PrismFinding(id, severity, title, message, suggestion, locations);
    }

    @SuppressWarnings("unchecked")
    private static PrismLocation parseLocation(Map<String, Object> m) {
        String path = stringOrNull(m.get("path"));
        PrismLines lines = null;
        Object linesRaw = m.get("lines");
        if (linesRaw instanceof Map<?, ?> lm) {
            Integer start = intOrNull(((Map<String, Object>) lm).get("start"));
            Integer end = intOrNull(((Map<String, Object>) lm).get("end"));
            lines = new PrismLines(start, end);
        }
        return new PrismLocation(path, lines);
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Long l) {
            return l.intValue();
        }
        if (o instanceof Double d) {
            return d.intValue();
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
