package gate.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
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
