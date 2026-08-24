package gate.web.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.util.List;
import java.util.Map;

/**
 * Jackson-backed JSON utility for gate-web.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

    /**
     * Parses a request body into a JSON object. Fail-closed: blank bodies yield an empty map,
     * but malformed JSON or non-object roots (arrays, scalars) are a USAGE error.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = MAPPER.readValue(text.trim(), Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.USAGE, "malformed JSON body");
        }
        throw new GateException(GateErrorCode.USAGE, "request body must be a JSON object");
    }

    public static String error(int errorCode, String errorName, String message, List<String> detail) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error_code", errorCode);
        body.put("error", errorName);
        body.put("message", message == null ? "" : message);
        body.put("detail", detail == null ? List.of() : detail);
        return write(body);
    }
}
