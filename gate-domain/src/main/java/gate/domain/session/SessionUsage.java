package gate.domain.session;

/**
 * Token usage (OpenAI-style: prompt/completion/total) — 执行文档-后端-web §5.2.
 */
public record SessionUsage(Long promptTokens, Long completionTokens, Long totalTokens) {

    public static final SessionUsage EMPTY = new SessionUsage(null, null, null);

    /** Sums two usages field-wise, treating null as 0; null result stays null when both are null. */
    public SessionUsage add(SessionUsage other) {
        if (other == null) {
            return this;
        }
        return new SessionUsage(
                addNullSafe(promptTokens, other.promptTokens),
                addNullSafe(completionTokens, other.completionTokens),
                addNullSafe(totalTokens, other.totalTokens));
    }

    private static Long addNullSafe(Long a, Long b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0L : a) + (b == null ? 0L : b);
    }
}
