package gate.domain.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One parameter-level problem in a request, so an agent or web client can fix exactly what is
 * wrong instead of guessing from a prose message (T-108).
 *
 * <p>{@code field} is the request key (nested indexes are reported as {@code labels[2]}),
 * {@code problem} a short machine-readable label ("missing required parameter", "wrong JSON type",
 * "invalid value", "unknown field", ...), {@code expected} the accepted shape/format and
 * {@code actual} the observed value (truncated, never a secret).
 */
public record FieldError(String field, String problem, String expected, String actual) {

    private static final int CAP = 160;

    public FieldError {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (problem == null || problem.isBlank()) {
            throw new IllegalArgumentException("problem must not be blank");
        }
        expected = cap(expected);
        actual = cap(actual);
    }

    /** Human-readable one-liner: {@code field: problem (expected X; got Y)}. */
    public String render() {
        StringBuilder sb = new StringBuilder(field).append(": ").append(problem);
        if (expected != null || actual != null) {
            sb.append(" (expected ").append(expected == null ? "valid value" : expected);
            if (actual != null) {
                sb.append("; got ").append(actual);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** Structured payload for JSON-RPC {@code error.data} / web {@code detail}. Nulls omitted. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("problem", problem);
        if (expected != null) {
            m.put("expected", expected);
        }
        if (actual != null) {
            m.put("actual", actual);
        }
        return m;
    }

    private static String cap(String v) {
        if (v == null || v.length() <= CAP) {
            return v;
        }
        return v.substring(0, CAP - 3) + "...";
    }
}
