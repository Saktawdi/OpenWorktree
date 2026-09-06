package gate.domain.session;

/**
 * One chronological segment of an assistant turn (ZCode-style timeline).
 *
 * <p>A turn is rarely "text, then all tools" — it interleaves: the model thinks, writes a
 * paragraph, runs tools, writes more. {@code content}/{@code toolCalls} alone cannot express
 * that order, so turn-persisting adapters also emit an ordered part list:
 *
 * <ul>
 *   <li>{@code text} — one model prose segment ({@link #text});</li>
 *   <li>{@code thinking} — one reasoning segment ({@link #text});</li>
 *   <li>{@code tool} — one tool invocation ({@link #name}, {@link #argumentsJson},
 *       {@link #resultJson}).</li>
 * </ul>
 *
 * <p>Persisted alongside the legacy flat fields; consumers that do not know parts keep working
 * off {@code content}/{@code toolCalls} unchanged.
 */
public record TurnPart(String type, String text, String name, String argumentsJson, String resultJson) {

    public static final String TEXT = "text";
    public static final String THINKING = "thinking";
    public static final String TOOL = "tool";

    public TurnPart {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        text = text == null ? "" : text;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null ? "" : argumentsJson;
    }

    public static TurnPart text(String text) {
        return new TurnPart(TEXT, text, null, null, null);
    }

    public static TurnPart thinking(String text) {
        return new TurnPart(THINKING, text, null, null, null);
    }

    public static TurnPart tool(String name, String argumentsJson, String resultJson) {
        return new TurnPart(TOOL, null, name, argumentsJson, resultJson);
    }

    public boolean isText() {
        return TEXT.equals(type);
    }

    public boolean isThinking() {
        return THINKING.equals(type);
    }

    public boolean isTool() {
        return TOOL.equals(type);
    }
}
