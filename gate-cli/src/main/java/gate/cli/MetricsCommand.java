package gate.cli;

import gate.application.H1Verdict;
import gate.application.MetricsService;
import gate.application.MetricsService.MetricRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate metrics export|verdict}: P4 cost telemetry export and H1 judgment
 * (执行文档 §4 P4, §15).
 *
 * <p><b>Export</b> ({@code gate metrics export --format csv|json}): dumps every review_result row
 * with its cost telemetry fields (token counts, token source, wall-clock, diff size, verdict,
 * review round). The export explicitly annotates which rows have precise token data vs degraded
 * (unavailable) — a reader is never misled into treating a wall-clock proxy as a precise token.
 *
 * <p><b>Verdict</b> ({@code gate metrics verdict}): computes the H1 three-tier classification
 * (ESTABLISHED / PARTIAL / REFUTED / INSUFFICIENT_SAMPLES). Only emits a judgment when
 * {@code sampleCount >= 20}; fewer samples produce a data snapshot without a classification.
 *
 * <p>H1 is <b>designed to be refutable</b> — the verdict must not be adjusted or sample-filtered
 * to make H1 "look true" (执行文档 §4 P4 hard constraint).
 */
@CommandLine.Command(name = "metrics",
        description = "Export cost telemetry and compute H1 verdict (P4)",
        subcommands = {
                MetricsCommand.Export.class,
                MetricsCommand.Verdict.class
        })
final class MetricsCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    abstract static class MetricsBase extends BaseCommand {
        MetricsService service() {
            GateComponents c = components();
            return new MetricsService(c.reviewResultRepository(), c.presubmitRepository(),
                    c.ticketRepository());
        }
    }

    /** {@code gate metrics export --format csv|json} */
    @CommandLine.Command(name = "export", description = "Export cost telemetry as CSV or JSON")
    static final class Export extends MetricsBase {

        @CommandLine.Option(names = "--format", defaultValue = "json",
                description = "Output format: csv or json (default: json)")
        String format;

        @Override
        public void run() {
            List<MetricRecord> records = service().export();
            switch (format.toLowerCase()) {
                case "csv" -> exportCsv(records);
                default -> exportJson(records);
            }
        }

        private void exportJson(List<MetricRecord> records) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (MetricRecord r : records) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("ticket_no", r.ticketNo());
                row.put("review_round", r.reviewRound());
                row.put("verdict", r.verdict());
                row.put("diff_bytes", r.diffBytes());
                row.put("diff_lines", r.diffLines());
                row.put("prompt_tokens", nullable(r.promptTokens()));
                row.put("completion_tokens", nullable(r.completionTokens()));
                row.put("total_tokens", nullable(r.totalTokens()));
                row.put("token_source", r.tokenSource() == null ? "unavailable" : r.tokenSource());
                row.put("review_wall_ms", nullable(r.reviewWallMs()));
                row.put("llm_wall_ms", nullable(r.llmWallMs()));
                row.put("exec_token_total", nullable(r.execTokenTotal()));
                row.put("exec_token_source", r.execTokenSource() == null ? "unavailable" : r.execTokenSource());
                rows.add(row);
            }
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("records", rows);
            payload.put("count", rows.size());
            JsonOut.emit(System.out, "metrics.export", payload);
        }

        private void exportCsv(List<MetricRecord> records) {
            StringBuilder sb = new StringBuilder();
            sb.append("ticket_no,review_round,verdict,diff_bytes,diff_lines,")
              .append("prompt_tokens,completion_tokens,total_tokens,token_source,")
              .append("review_wall_ms,llm_wall_ms,exec_token_total,exec_token_source\n");
            for (MetricRecord r : records) {
                sb.append(r.ticketNo()).append(',')
                  .append(r.reviewRound()).append(',')
                  .append(r.verdict()).append(',')
                  .append(r.diffBytes()).append(',')
                  .append(r.diffLines()).append(',')
                  .append(csvNullable(r.promptTokens())).append(',')
                  .append(csvNullable(r.completionTokens())).append(',')
                  .append(csvNullable(r.totalTokens())).append(',')
                  .append(r.tokenSource() == null ? "unavailable" : r.tokenSource()).append(',')
                  .append(csvNullable(r.reviewWallMs())).append(',')
                  .append(csvNullable(r.llmWallMs())).append(',')
                  .append(csvNullable(r.execTokenTotal())).append(',')
                  .append(r.execTokenSource() == null ? "unavailable" : r.execTokenSource())
                  .append('\n');
            }
            System.out.print(sb.toString());
            System.out.flush();
        }

        private static String nullable(Long v) {
            return v == null ? "" : v.toString();
        }

        private static String csvNullable(Long v) {
            return v == null ? "" : v.toString();
        }
    }

    /** {@code gate metrics verdict}: compute H1 three-tier classification. */
    @CommandLine.Command(name = "verdict", description = "Compute the H1 three-tier verdict")
    static final class Verdict extends MetricsBase {

        @Override
        public void run() {
            H1Verdict v = service().verdict();
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("classification", v.classification());
            payload.put("cost_ratio_median", Double.isNaN(v.costRatioMedian()) ? "" : String.valueOf(v.costRatioMedian()));
            payload.put("first_pass_rate", String.valueOf(v.firstPassRate()));
            payload.put("sample_count", v.sampleCount());
            payload.put("metric_basis", v.metricBasis());
            payload.put("degradation_note", v.degradationNote());
            payload.put("min_samples_for_verdict", H1Verdict.MIN_SAMPLES_FOR_VERDICT);
            JsonOut.emit(System.out, "metrics.verdict", payload);
        }
    }
}
