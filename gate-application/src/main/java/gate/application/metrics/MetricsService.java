package gate.application.metrics;

import gate.ports.PresubmitRepository;
import gate.ports.ReviewResultRepository;
import gate.ports.ReviewResultRepository.CostRecord;
import gate.ports.ReviewResultRepository.ReviewResultRow;
import gate.ports.TicketRepository;
import gate.domain.policy.Decision;
import gate.domain.ticket.Ticket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the H1 metrics (执行文档 §4 P4, §15) and exports the cost telemetry for external analysis.
 *
 * <p><b>Bypass only</b>: this service reads data the review path already recorded. It never writes,
 * never blocks, and never alters a verdict. Its sole job is to make the founding hypothesis H1
 * <b>refutable by data</b>.
 *
 * <h3>Two metrics</h3>
 * <ul>
 *   <li><b>审核成本占比中位数</b> = {@code review_token_cost / (exec_token_cost + review_token_cost)},
 *       median across tickets. When review tokens are unavailable (the common case), the ratio is
 *       NaN and the basis degrades to review_round + diff_size + wall-clock.</li>
 *   <li><b>一次通过率</b> = fraction of tickets whose first round (round==1) got verdict==PASS.</li>
 * </ul>
 *
 * <h3>Honest degradation</h3>
 * prism JSON exposes {@code timing.totalMs/llmMs} but <b>no usage/token field</b>
 * (docs/archive/prism-schema-validation.md). exec_token depends on the agent CLI, which this project does not
 * spawn. So the export explicitly annotates {@code token_source} per row, and the verdict's
 * {@code metricBasis} is {@code "degraded"} when token data is missing.
 */
public final class MetricsService {

    private final ReviewResultRepository reviewResults;
    private final PresubmitRepository presubmits;
    private final TicketRepository tickets;

    public MetricsService(ReviewResultRepository reviewResults, PresubmitRepository presubmits,
                          TicketRepository tickets) {
        this.reviewResults = reviewResults;
        this.presubmits = presubmits;
        this.tickets = tickets;
    }

    /** A single exported metric record (one per review_result row). */
    public record MetricRecord(
            String ticketNo,
            int reviewRound,
            String verdict,
            long diffBytes,
            long diffLines,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            String tokenSource,
            Long reviewWallMs,
            Long llmWallMs,
            Long execTokenTotal,
            String execTokenSource) {
    }

    /** Exports all review results with their cost telemetry, for CSV/JSON output. */
    public List<MetricRecord> export() {
        List<ReviewResultRow> rows = reviewResults.findAllForMetrics();
        List<Ticket> allTickets = tickets.findAll();
        Map<String, Ticket> ticketByNo = new LinkedHashMap<>();
        for (Ticket t : allTickets) {
            ticketByNo.put(t.ticketNo(), t);
        }

        // Build a map from presubmit_id → ticket_no (needed because review_result references presubmit, not ticket).
        // We get this by looking up each review result's presubmit, but that's N queries. Instead, we
        // join via the presubmits table: presubmit.ticket_no. Since PresubmitRepository doesn't have
        // a findById, we use the fact that each review_result row has presubmit_id and we can look up
        // the presubmit by querying presubmits per ticket. For efficiency in P4, we iterate tickets.
        Map<Long, String> presubmitIdToTicket = new LinkedHashMap<>();
        for (Ticket t : allTickets) {
            for (var p : presubmits.findAllByTicket(t.ticketNo())) {
                presubmitIdToTicket.put(p.id(), t.ticketNo());
            }
        }

        List<MetricRecord> out = new ArrayList<>();
        for (ReviewResultRow r : rows) {
            String ticketNo = presubmitIdToTicket.getOrDefault(r.presubmitId(), "?");
            Ticket ticket = ticketByNo.get(ticketNo);
            out.add(new MetricRecord(
                    ticketNo,
                    roundOf(presubmitIdToTicket, r),
                    r.verdict().name(),
                    r.diffBytes() == null ? 0L : r.diffBytes(),
                    r.diffLines() == null ? 0L : r.diffLines(),
                    r.promptTokens(),
                    r.completionTokens(),
                    r.totalTokens(),
                    r.tokenSource() == null ? "unavailable" : r.tokenSource(),
                    r.reviewWallMs(),
                    r.llmWallMs(),
                    ticket == null ? null : ticket.execTokenTotal(),
                    ticket == null ? null : ticket.execTokenSource()));
        }
        return out;
    }

    /** Computes the H1 verdict from the current data (执行文档 §4 P4 三档阈值). */
    public H1Verdict verdict() {
        List<MetricRecord> records = export();
        int sampleCount = uniqueTicketCount(records);

        if (sampleCount < H1Verdict.MIN_SAMPLES_FOR_VERDICT) {
            return new H1Verdict(H1Verdict.INSUFFICIENT_SAMPLES,
                    Double.NaN, Double.NaN, sampleCount,
                    H1Verdict.BASIS_DEGRADED,
                    "sample count < " + H1Verdict.MIN_SAMPLES_FOR_VERDICT
                            + " — data snapshot only, no judgment (执行文档 §4 P4)");
        }

        double firstPassRate = computeFirstPassRate(records);
        double costRatioMedian = computeCostRatioMedian(records);

        boolean hasTokenData = records.stream().anyMatch(r -> r.totalTokens() != null);
        String basis = hasTokenData ? H1Verdict.BASIS_PRECISE : H1Verdict.BASIS_DEGRADED;
        String degradationNote = hasTokenData ? "" : buildDegradationNote(records);

        // Three-tier classification (执行文档 §4 P4).
        // cost ratio > 40% OR first-pass rate < 40% → REFUTED
        // cost ratio 20%-40% → PARTIAL
        // cost ratio < 20% AND first-pass rate > 60% → ESTABLISHED
        String classification;
        if (!Double.isNaN(costRatioMedian)) {
            if (costRatioMedian > 0.40 || firstPassRate < 0.40) {
                classification = H1Verdict.REFUTED;
            } else if (costRatioMedian >= 0.20) {
                classification = H1Verdict.PARTIAL;
            } else if (firstPassRate > 0.60) {
                classification = H1Verdict.ESTABLISHED;
            } else {
                // cost ratio < 20% but first-pass rate <= 60% — not clearly established, lean partial
                classification = H1Verdict.PARTIAL;
            }
        } else {
            // Token data unavailable — cannot compute cost ratio precisely. Use degraded basis:
            // if first-pass rate < 40%, H1 is refuted; otherwise the cost ratio is indeterminate.
            if (firstPassRate < 0.40) {
                classification = H1Verdict.REFUTED;
            } else {
                classification = H1Verdict.PARTIAL;
            }
        }

        return new H1Verdict(classification, costRatioMedian, firstPassRate, sampleCount,
                basis, degradationNote);
    }

    // --- metric computations ---

    /** 一次通过率 = fraction of tickets whose round-1 review got PASS. */
    public static double computeFirstPassRate(List<MetricRecord> records) {
        Map<String, Integer> firstRoundVerdict = new LinkedHashMap<>();
        for (MetricRecord r : records) {
            if ("?".equals(r.ticketNo())) continue;
            Integer existing = firstRoundVerdict.get(r.ticketNo());
            // Keep the round-1 verdict; if no round-1, keep the lowest round.
            if (existing == null || r.reviewRound() < existing) {
                firstRoundVerdict.put(r.ticketNo(), r.reviewRound());
            }
        }
        if (firstRoundVerdict.isEmpty()) {
            return 0.0;
        }
        // Count tickets whose round-1 review was PASS. A ticket is "first-pass" if it has a
        // review record at round 1 with verdict PASS.
        long firstPassCount = records.stream()
                .filter(r -> r.reviewRound() == 1 && "PASS".equals(r.verdict()))
                .map(MetricRecord::ticketNo)
                .distinct()
                .count();
        return (double) firstPassCount / firstRoundVerdict.size();
    }

    /**
     * 审核成本占比中位数 = review_token_cost / (exec_token_cost + review_token_cost), median across
     * tickets that have token data. Returns NaN if no ticket has token data (degraded basis).
     */
    public static double computeCostRatioMedian(List<MetricRecord> records) {
        // Aggregate per-ticket: sum review tokens, sum exec tokens (from ticket record).
        Map<String, long[]> perTicket = new LinkedHashMap<>(); // ticketNo → [reviewTotal, execTotal]
        for (MetricRecord r : records) {
            if ("?".equals(r.ticketNo())) continue;
            long[] agg = perTicket.computeIfAbsent(r.ticketNo(), k -> new long[]{0, 0});
            if (r.totalTokens() != null) {
                agg[0] += r.totalTokens();
            }
            if (r.execTokenTotal() != null) {
                agg[1] += r.execTokenTotal();
            }
        }
        List<Double> ratios = new ArrayList<>();
        for (long[] agg : perTicket.values()) {
            long reviewTotal = agg[0];
            long execTotal = agg[1];
            if (reviewTotal <= 0) {
                continue; // no review token data for this ticket
            }
            double ratio = (double) reviewTotal / (execTotal + reviewTotal);
            ratios.add(ratio);
        }
        if (ratios.isEmpty()) {
            return Double.NaN; // degraded basis
        }
        Collections.sort(ratios);
        int mid = ratios.size() / 2;
        return ratios.size() % 2 == 0
                ? (ratios.get(mid - 1) + ratios.get(mid)) / 2.0
                : ratios.get(mid);
    }

    public static int uniqueTicketCount(List<MetricRecord> records) {
        return (int) records.stream()
                .map(MetricRecord::ticketNo)
                .filter(t -> !"?".equals(t))
                .distinct()
                .count();
    }

    private static String buildDegradationNote(List<MetricRecord> records) {
        boolean anyReviewToken = records.stream().anyMatch(r -> r.totalTokens() != null);
        boolean anyExecToken = records.stream().anyMatch(r -> r.execTokenTotal() != null);
        StringBuilder sb = new StringBuilder();
        if (!anyReviewToken) {
            sb.append("review_token_cost unavailable (prism JSON exposes no usage field; ")
              .append("using review_round + diff_size + wall-clock as degraded proxy). ");
        }
        if (!anyExecToken) {
            sb.append("exec_token_cost unavailable (agent CLI token usage not captured; ")
              .append("cost ratio denominator is incomplete).");
        }
        return sb.toString().trim();
    }

    private int roundOf(Map<Long, String> presubmitIdToTicket, ReviewResultRow r) {
        // The round isn't directly on review_result; we derive it from the presubmit's review_round.
        // Since PresubmitRow has reviewRound, and we have the presubmit_id, we look it up.
        // For efficiency, we assume the caller iterated tickets above. Fallback: parse from presubmits.
        // Actually, let's just query: this is a read-only metrics path, not performance-critical.
        var p = presubmits.findById(r.presubmitId());
        return p.map(PresubmitRepository.PresubmitRow::reviewRound).orElse(0);
    }
}
