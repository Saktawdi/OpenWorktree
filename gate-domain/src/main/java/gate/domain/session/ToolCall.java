package gate.domain.session;

/** A tool call captured in a session message. */
public record ToolCall(String name, String argumentsJson, String resultJson) {

    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        argumentsJson = argumentsJson == null ? "" : argumentsJson;
    }
}
