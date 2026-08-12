package gate.domain.review;

/**
 * A single normalised review finding (架构落地执行文档 §5.3).
 *
 * @param severity    normalised closed set; unknown engine words map to {@link Severity#BLOCKER}
 * @param rawSeverity the engine's own word, kept verbatim for audit (prism: low/med/high)
 * @param path        repo-relative, '/'-separated; never absolute
 * @param lineStart   nullable; single-line findings set {@code lineEnd == lineStart}
 * @param lineEnd     nullable
 * @param ruleId      nullable — prism only has hash ids, there is no stable rule vocabulary
 */
public record Finding(
        Severity severity,
        String rawSeverity,
        String path,
        Integer lineStart,
        Integer lineEnd,
        String ruleId,
        String message,
        String suggestion) {

    public Finding {
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
    }
}
