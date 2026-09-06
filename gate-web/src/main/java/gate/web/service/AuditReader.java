package gate.web.service;

import gate.adapters.audit.HashChainAuditLog;
import gate.application.util.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only access to the hash-chained audit log for the evidence UI (需求文档 §八：聚合端点).
 *
 * <p>{@link gate.ports.store.AuditLog} is write-only by design; this reader is the single web-side
 * consumer that parses the JSONL back into maps. A corrupted line is skipped here — chain
 * verification (also exposed) is what surfaces tampering, not the projection.
 */
public final class AuditReader {

    private final HashChainAuditLog log;

    public AuditReader(HashChainAuditLog log) {
        this.log = log;
    }

    public HashChainAuditLog.ChainReport verify() {
        return log.verifyChainReport();
    }

    /**
     * All audit lines whose {@code ticket_no} matches, oldest first. At most {@code limit} rows are
     * returned (the rest only counted) so a huge file cannot blow up the response.
     */
    public List<Map<String, Object>> linesForTicket(String ticketNo, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        int[] total = {0};
        for (String line : log.readLines()) {
            Map<String, Object> row = parseLine(line);
            if (row == null || !ticketNo.equals(row.get("ticket_no"))) {
                continue;
            }
            total[0]++;
            if (total[0] <= limit) {
                out.add(row);
            }
        }
        out.forEach(r -> r.put("_total", total[0]));
        return out;
    }

    /**
     * The latest {@code review.*} audit row for one ticket round, as the persisted decision view:
     * {@code verdict} (from the kind suffix), {@code reason} and {@code detail} (fields written by
     * ReviewHandler). Older tickets may lack the detail fields — callers must fall back.
     */
    public Optional<Map<String, Object>> latestReviewDecision(String ticketNo, int round) {
        Map<String, Object> best = null;
        for (Map<String, Object> row : linesForTicket(ticketNo, 500)) {
            Object kind = row.get("kind");
            if (!(kind instanceof String k) || !k.startsWith("review.")) {
                continue;
            }
            Object r = row.get("review_round");
            if (r instanceof Number n && n.intValue() == round) {
                best = row;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("verdict", ((String) best.get("kind")).substring("review.".length()).toUpperCase());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) best.getOrDefault("fields", Map.of());
        decision.put("reason", fields.getOrDefault("reason", ""));
        List<String> detail = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Object item = fields.get("detail." + i);
            if (item == null) {
                break;
            }
            detail.add(String.valueOf(item));
        }
        decision.put("detail", detail);
        return Optional.of(decision);
    }

    /** Parses one audit JSONL line; returns null for blank/corrupted lines. */
    public static Map<String, Object> parseLine(String line) {
        try {
            Object parsed = MiniJson.parse(line);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                Object fields = out.get("fields");
                if (fields instanceof Map<?, ?> f) {
                    Map<String, Object> flat = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : f.entrySet()) {
                        flat.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    out.put("fields", flat);
                }
                return out;
            }
        } catch (Exception ignore) {
            // corrupted line: skip here; chain verification flags it as a broken link
        }
        return null;
    }
}
