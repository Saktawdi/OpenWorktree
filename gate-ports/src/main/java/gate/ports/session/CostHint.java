package gate.ports.session;
import gate.ports.infra.Clock;


import java.util.Optional;

/**
 * Cost telemetry extracted from a review engine's raw output (P4, 执行文档 §4 P4, §15).
 *
 * <p>This is a <b>bypass</b> data carrier — it is recorded after a review completes and must never
 * affect the verdict or block publish (执行文档 §4 P4 hard constraint: "成本记录失败不能让 publish 失败").
 *
 * <p><b>Honest degradation</b> (执行文档 §4 P4 footnote): prism's JSON output does NOT expose
 * usage/token fields (confirmed in docs/archive/prism-schema-validation.md). exec_token_cost depends on the agent
 * CLI emitting usage, which this project does not control (it does not spawn the agent). So:
 * <ul>
 *   <li>{@code tokenSource} = {@code "unavailable"} when the engine gives no token data (the common
 *       case for prism); {@code promptTokens}/{@code completionTokens}/{@code totalTokens} are null.</li>
 *   <li>{@code reviewWallMs} / {@code llmWallMs} are populated from prism's {@code timing.totalMs} /
 *       {@code timing.llmMs} when present — these are the <b>degraded basis</b> for the H1 verdict.</li>
 * </ul>
 *
 * <p>The export and verdict commands <b>explicitly annotate</b> when the basis is degraded so a reader
 * of the data is not misled into treating a wall-clock proxy as a precise token count.
 *
 * @param promptTokens     LLM prompt tokens, if the engine/gateway exposed them; null otherwise.
 * @param completionTokens LLM completion tokens, if exposed; null otherwise.
 * @param totalTokens      LLM total tokens, if exposed; null otherwise.
 * @param tokenSource      where the token data came from: "engine_json" | "gateway_usage" | "unavailable".
 * @param reviewWallMs     total review wall-clock (ms), from prism timing.totalMs; null if unavailable.
 * @param llmWallMs        LLM-only wall-clock (ms), from prism timing.llmMs; null if unavailable.
 */
public record CostHint(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        String tokenSource,
        Long reviewWallMs,
        Long llmWallMs) {

    /** A cost hint with no token data, only timing. This is the common prism case. */
    public static CostHint timingOnly(Long reviewWallMs, Long llmWallMs) {
        return new CostHint(null, null, null, "unavailable", reviewWallMs, llmWallMs);
    }

    /** A cost hint with no data at all (e.g. manual review). */
    public static final CostHint EMPTY = new CostHint(null, null, null, "unavailable", null, null);

    /** Whether token counts are available (vs degraded to timing/rounds/diff-size). */
    public boolean hasTokenData() {
        return totalTokens != null;
    }
}
