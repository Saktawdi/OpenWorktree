package gate.web.controller;

import gate.adapters.audit.HashChainAuditLog;
import gate.domain.blob.BlobRef;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.publish.PublishIntent;
import gate.domain.ticket.Ticket;
import gate.ports.store.ApprovalStore;
import gate.ports.store.BlobStore;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.PublishIntentRepository;
import gate.ports.store.ReviewResultRepository;
import gate.ports.store.TicketRepository;
import gate.ports.store.TicketStageChangeRepository;
import gate.web.service.AuditReader;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evidence-chain Controller: read-only projection of the facts the gate already persisted,
 * grouped by review round (需求文档 §五：以轮次为骨架的垂直时间线的数据源).
 *
 * <p>Aggregates presubmit rows + review evidence blobs + publish intents + stage changes +
 * ticket-scoped audit events into one {@code GET /api/tickets/{ticketNo}/evidence} response,
 * plus a hash-chain integrity report. Pure projection — no new state, no writes.
 */
public final class EvidenceController implements WebController {

    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final PublishIntentRepository publishIntents;
    private final TicketStageChangeRepository stageChanges;
    private final ApprovalStore approvals;
    private final BlobStore blobStore;
    private final AuditReader auditReader;

    public EvidenceController(TicketRepository tickets, PresubmitRepository presubmits,
                              ReviewResultRepository reviewResults, PublishIntentRepository publishIntents,
                              TicketStageChangeRepository stageChanges, ApprovalStore approvals,
                              BlobStore blobStore, HashChainAuditLog auditLog) {
        this(tickets, presubmits, reviewResults, publishIntents, stageChanges, approvals,
                blobStore, new AuditReader(auditLog));
    }

    public EvidenceController(TicketRepository tickets, PresubmitRepository presubmits,
                              ReviewResultRepository reviewResults, PublishIntentRepository publishIntents,
                              TicketStageChangeRepository stageChanges, ApprovalStore approvals,
                              BlobStore blobStore, AuditReader auditReader) {
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.reviewResults = reviewResults;
        this.publishIntents = publishIntents;
        this.stageChanges = stageChanges;
        this.approvals = approvals;
        this.blobStore = blobStore;
        this.auditReader = auditReader;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/tickets/{ticketNo}/evidence", this::evidence);
    }

    public void evidence(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket ticket = tickets.find(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));

        // audit lines are filtered by the ticket_no field every gate event carries
        List<Map<String, Object>> ticketAudit = auditReader.linesForTicket(ticketNo, 400);
        int ticketAuditTotal = ticketAudit.isEmpty() ? 0
                : ((Number) ticketAudit.get(ticketAudit.size() - 1).getOrDefault("_total", 0)).intValue();
        ticketAudit.forEach(r -> r.remove("_total"));

        List<Map<String, Object>> rounds = new ArrayList<>();
        for (var row : presubmits.findAllByTicket(ticketNo)) {
            rounds.add(roundMap(row));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("created_at", ticket.createdAt() == null ? null : ticket.createdAt().toString());
        body.put("chain", chainMap());
        body.put("rounds", rounds);
        body.put("stage_changes", stageChangeMaps(ticketNo));
        body.put("publish_intents", publishIntentMaps(ticketNo));
        body.put("audit_events", ticketAudit);
        body.put("audit_events_total", ticketAuditTotal);
        body.put("audit_truncated", ticketAuditTotal > ticketAudit.size());
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private Map<String, Object> roundMap(PresubmitRepository.PresubmitRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("review_round", row.reviewRound());
        m.put("tree_hash", row.treeHash().hex());
        m.put("base_commit", row.baseCommit().hex());
        m.put("target_ref", row.targetRef());
        m.put("diff_bytes", row.diffBytes());
        m.put("diff_sha256", row.diffSha256());
        m.put("created_at", row.createdAt().toString());
        // evidence.json (report/failure shape written by EvidenceCodec) — raw passes through
        m.put("evidence", readEvidenceBlob(row));
        m.put("changed_paths", changedPathsOf(row));
        var review = reviewResults.findLatestForPresubmit(row.id());
        if (review.isPresent()) {
            var r = review.get();
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("verdict", r.verdict().name());
            rv.put("engine_id", r.engine().engineId());
            rv.put("engine_version", r.engine().engineVersion());
            rv.put("model_name", r.engine().modelName());
            rv.put("covered_ok", r.coveredOk());
            rv.put("degraded", r.degraded());
            rv.put("created_at", r.createdAt().toString());
            Map<String, Object> cost = new LinkedHashMap<>();
            cost.put("prompt_tokens", r.promptTokens());
            cost.put("completion_tokens", r.completionTokens());
            cost.put("total_tokens", r.totalTokens());
            cost.put("review_wall_ms", r.reviewWallMs());
            rv.put("cost", cost);
            m.put("review", rv);
        }
        // 判决的 reason/detail 只持久化在审计日志（review.* 行）；回读让判决卡片能"解释自己"
        auditReader.latestReviewDecision(row.ticketNo(), row.reviewRound())
                .ifPresent(d -> m.put("decision", d));
        return m;
    }

    private Object readEvidenceBlob(PresubmitRepository.PresubmitRow row) {
        var review = reviewResults.findLatestForPresubmit(row.id());
        if (review.isEmpty() || review.get().findingsBlobPath() == null) {
            return null;
        }
        try {
            byte[] raw = blobStore.get(new BlobRef(review.get().findingsBlobPath(), 0, "0".repeat(64)));
            return Json.mapper().readValue(new String(raw, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return Map.of("kind", "unreadable", "detail", String.valueOf(e.getMessage()));
        }
    }

    private List<String> changedPathsOf(PresubmitRepository.PresubmitRow row) {
        try {
            String diff = new String(blobStore.get(
                    new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256())), StandardCharsets.UTF_8);
            List<String> paths = new ArrayList<>();
            for (String line : diff.split("\n")) {
                if (line.startsWith("diff --git a/")) {
                    int end = line.indexOf(" b/");
                    paths.add(end > 13 ? line.substring(13, end) : line.substring(13));
                }
            }
            return paths;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> chainMap() {
        HashChainAuditLog.ChainReport r = auditReader.verify();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("total_lines", r.totalLines());
        m.put("broken_at_line", r.brokenAtLine());
        return m;
    }

    private List<Map<String, Object>> stageChangeMaps(String ticketNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : stageChanges.findByTicket(ticketNo)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("round", r.round());
            m.put("from_stage", r.fromStage().name());
            m.put("to_stage", r.effectiveToStage().name());
            m.put("kind", r.isRevive()
                    ? "restart"
                    : r.effectiveToStage() == gate.domain.ticket.TicketStage.DONE ? "force_complete" : "cancel");
            m.put("reason", r.reason());
            m.put("created_at", r.createdAt() == null ? null : r.createdAt().toString());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> publishIntentMaps(String ticketNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublishIntent intent : publishIntents.findByTicket(ticketNo)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("review_round", intent.reviewRound());
            m.put("tree_hash", intent.treeHash().hex());
            m.put("base_commit", intent.baseCommit().hex());
            m.put("target_ref", intent.targetRef());
            m.put("commit_sha", intent.commitSha() == null ? null : intent.commitSha().hex());
            m.put("status", intent.status().name());
            m.put("ref_before", intent.observedRefBefore());
            m.put("ref_after", intent.observedRefAfter());
            m.put("created_at", intent.createdAt().toString());
            m.put("finished_at", intent.finishedAt() == null ? null : intent.finishedAt().toString());
            m.put("approval_id", intent.approvalId() == null ? null : intent.approvalId().value());
            if (intent.approvalId() != null) {
                m.put("approval_consumed", approvals.isConsumed(intent.approvalId()));
            }
            out.add(m);
        }
        return out;
    }
}
