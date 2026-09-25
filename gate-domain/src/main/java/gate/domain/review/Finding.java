package gate.domain.review;

/**
 * A single normalised review finding (架构落地执行文档 §5.3).
 *
 * @param severity     normalised closed set; unknown engine words map to {@link Severity#BLOCKER}
 * @param rawSeverity  the engine's own word, kept verbatim for audit (prism: low/med/high)
 * @param path         repo-relative, '/'-separated; never absolute
 * @param lineStart    nullable; single-line findings set {@code lineEnd == lineStart}
 * @param lineEnd      nullable
 * @param ruleId       nullable — prism only has hash ids, there is no stable rule vocabulary
 * @param message      human-readable description of the problem
 * @param suggestion   nullable — what the author should change
 * @param existingCode nullable — the engine's verbatim excerpt of the code the finding refers to.
 *                     Line numbers reported by an LLM drift; the excerpt does not: with an excerpt
 *                     on record the anchoring step can re-derive the true location from the diff
 *                     deterministically, and a repair loop receives an exact target instead of a
 *                     guessed line. Kept verbatim (no trimming beyond surrounding whitespace) for
 *                     audit.
 */
public record Finding(
        Severity severity,
        String rawSeverity,
        String path,
        Integer lineStart,
        Integer lineEnd,
        String ruleId,
        String message,
        String suggestion,
        String existingCode) {

    /** Back-compatible 8-arg constructor for call sites without an excerpt (manual findings). */
    public Finding(Severity severity, String rawSeverity, String path, Integer lineStart, Integer lineEnd,
                   String ruleId, String message, String suggestion) {
        this(severity, rawSeverity, path, lineStart, lineEnd, ruleId, message, suggestion, null);
    }

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
