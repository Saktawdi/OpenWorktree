package gate.application.metrics;

/**
 * The H1 verdict (执行文档 §4 P4, §15).
 *
 * <p>The project's founding hypothesis H1 is that a review gate adds acceptable cost. It is
 * <b>designed to be refutable by data</b> — the verdict must not be adjusted or sample-filtered to
 * make H1 "look true" (执行文档 §4 P4 hard constraint).
 *
 * <h3>Two metrics</h3>
 * <ul>
 *   <li><b>审核成本占比中位数</b> = {@code review_token_cost / (exec_token_cost + review_token_cost)},
 *       median across tickets. When token data is unavailable (the common case — prism JSON has no
 *       usage, and exec_token depends on the agent CLI), this falls back to a degraded basis and
 *       {@code metricBasis} = {@code "degraded"}.</li>
 *   <li><b>一次通过率</b> = fraction of tickets whose first review round ({@code review_round == 1})
 *       got {@code verdict == PASS}.</li>
 * </ul>
 *
 * <h3>Three-tier classification (执行文档 §4 P4)</h3>
 * <ul>
 *   <li>{@code ESTABLISHED} — cost ratio < 20% AND first-pass rate > 60% → H1 成立, consider
 *       expanding.</li>
 *   <li>{@code PARTIAL} — cost ratio 20%–40% → 部分成立, optimize incremental review then re-measure.</li>
 *   <li>{@code REFUTED} — cost ratio > 40% OR first-pass rate < 40% → H1 证伪, trigger §7 stop-loss
 *       (reduce to a manually-triggered review tool).</li>
 * </ul>
 *
 * <p>Judgment is only emitted when {@code sampleCount >= 20} (执行文档 §4 P4). With fewer samples,
 * the verdict is {@code INSUFFICIENT_SAMPLES} and the data snapshot is exported without a
 * classification.
 *
 * @param classification   ESTABLISHED | PARTIAL | REFUTED | INSUFFICIENT_SAMPLES
 * @param costRatioMedian  审核成本占比中位数; {@link Double#NaN} if unavailable (degraded basis)
 * @param firstPassRate    一次通过率 (0.0–1.0)
 * @param sampleCount       number of tickets in the sample
 * @param metricBasis       "precise_tokens" if token data is available; "degraded" if using
 *                          review_round + diff_size + wall-clock proxy
 * @param degradationNote   human-readable explanation of what is degraded/missing (non-empty when
 *                          metricBasis = "degraded")
 */
public record H1Verdict(
        String classification,
        double costRatioMedian,
        double firstPassRate,
        int sampleCount,
        String metricBasis,
        String degradationNote) {

    public static final String ESTABLISHED = "ESTABLISHED";
    public static final String PARTIAL = "PARTIAL";
    public static final String REFUTED = "REFUTED";
    public static final String INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES";

    public static final String BASIS_PRECISE = "precise_tokens";
    public static final String BASIS_DEGRADED = "degraded";

    /** Minimum sample count for a judgment (执行文档 §4 P4). */
    public static final int MIN_SAMPLES_FOR_VERDICT = 20;
}
