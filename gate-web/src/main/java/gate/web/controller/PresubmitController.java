package gate.web.controller;

import gate.adapters.git.GitCli;
import gate.application.GateService;
import gate.application.presubmit.PresubmitCommand;
import gate.application.presubmit.PresubmitResult;
import gate.domain.blob.BlobRef;
import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.ticket.Ticket;
import gate.ports.store.BlobStore;
import gate.ports.store.PresubmitRepository;
import gate.ports.store.ReviewResultRepository;
import gate.ports.infra.TicketLockManager;
import gate.ports.store.TicketRepository;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presubmit & Diff Controller.
 * Owns /api/tickets/{ticketNo}/presubmit*, diff, review-result routes.
 */
public final class PresubmitController implements WebController {

    private static final int EOL_NOISE_THRESHOLD_CHARS = 100_000;

    private final GateService gateService;
    private final TicketRepository tickets;
    private final PresubmitRepository presubmits;
    private final ReviewResultRepository reviewResults;
    private final BlobStore blobStore;
    private final TicketLockManager ticketLockManager;
    private final GitCli git;
    private final GateConfig config;
    private final gate.ports.store.AuditLog auditLog;

    public PresubmitController(GateService gateService, TicketRepository tickets,
                               PresubmitRepository presubmits, ReviewResultRepository reviewResults,
                               BlobStore blobStore, TicketLockManager ticketLockManager,
                               GitCli git, GateConfig config) {
        this(gateService, tickets, presubmits, reviewResults, blobStore, ticketLockManager,
                git, config, null);
    }

    public PresubmitController(GateService gateService, TicketRepository tickets,
                               PresubmitRepository presubmits, ReviewResultRepository reviewResults,
                               BlobStore blobStore, TicketLockManager ticketLockManager,
                               GitCli git, GateConfig config, gate.ports.store.AuditLog auditLog) {
        this.gateService = gateService;
        this.tickets = tickets;
        this.presubmits = presubmits;
        this.reviewResults = reviewResults;
        this.blobStore = blobStore;
        this.ticketLockManager = ticketLockManager;
        this.git = git;
        this.config = config;
        this.auditLog = auditLog;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/tickets/{ticketNo}/presubmit", this::presubmit);
        app.get("/api/tickets/{ticketNo}/diff", this::workingDiff);
        app.get("/api/tickets/{ticketNo}/review-result", this::reviewResult);
        app.get("/api/tickets/{ticketNo}/presubmits", this::presubmitList);
        app.get("/api/tickets/{ticketNo}/presubmit/{round}/diff", this::presubmitDiff);
    }

    public void presubmit(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        try (AutoCloseable ignored = ticketLockManager.tryAcquire(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.REJECT_PRECONDITION,
                        "session in progress on this clone; presubmit refused while a session is active"))) {
            PresubmitResult r = gateService.presubmit(new PresubmitCommand(ticketNo));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticket_no", r.ticketNo());
            body.put("review_round", r.reviewRound());
            body.put("tree_hash", r.treeHash());
            body.put("base_commit", r.baseCommit());
            body.put("target_ref", r.targetRef());
            body.put("diff_bytes", r.diffBytes());
            body.put("changed_paths", r.changedPaths());
            body.put("integrity_warnings", r.integrity().warnings().stream()
                    .map(v -> v.rule() + ":" + v.detail()).toList());
            ctx.status(HttpStatus.OK);
            ctx.json(body);
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "presubmit lock failed", e);
        }
    }

    public void workingDiff(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket t = tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Path clone = Path.of(t.clonePath());
        if (!Files.isDirectory(clone)) {
            throw new GateException(GateErrorCode.USAGE,
                    "clone directory does not exist for " + ticketNo + ": " + clone);
        }
        String baseCommit = null;
        var latestPresubmit = presubmits.findLatest(ticketNo);
        if (latestPresubmit.isPresent()) {
            baseCommit = latestPresubmit.get().baseCommit().hex();
        }
        // V19 快速模式: no presubmit round ever exists, and the diff is the user's real workspace —
        // diff against HEAD so staged work is not silently dropped from the view.
        boolean diffHead = baseCommit != null || t.isSuper();
        gate.ports.infra.ProcessRunner.ProcRun tracked = diffHead
                ? git.run(clone, Map.of(), "diff", "HEAD")
                : git.run(clone, Map.of(), "diff");
        String trackedDiff = tracked.ok() ? tracked.stdout() : "";

        String eolWarning = null;
        if (trackedDiff.length() > EOL_NOISE_THRESHOLD_CHARS) {
            gate.ports.infra.ProcessRunner.ProcRun normalized = diffHead
                    ? git.run(clone, Map.of(), "diff", "HEAD", "--ignore-cr-at-eol")
                    : git.run(clone, Map.of(), "diff", "--ignore-cr-at-eol");
            if (normalized.ok() && normalized.stdout().length() * 10 < trackedDiff.length()) {
                eolWarning = "已忽略大量仅换行符（CRLF/LF）差异：该 clone 的检出未在 core.autocrlf=false 下进行，"
                        + "建议重建工作区；以下仅显示真实的内容变更。";
                trackedDiff = normalized.stdout();
            }
        }

        StringBuilder diff = new StringBuilder();
        if (!trackedDiff.isBlank()) {
            diff.append(trackedDiff.stripTrailing()).append('\n');
        }
        gate.ports.infra.ProcessRunner.ProcRun untracked = git.run(clone, Map.of(), "ls-files", "--others", "--exclude-standard");
        if (untracked.ok()) {
            for (String file : untracked.stdout().split("\n")) {
                String rel = file.trim();
                if (rel.isEmpty()) {
                    continue;
                }
                appendNewFileDiff(diff, clone, rel);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("source", "working");
        body.put("base_commit", baseCommit);
        body.put("diff", diff.toString());
        if (eolWarning != null) {
            body.put("eol_warning", eolWarning);
        }
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void reviewResult(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        var presubmitRow = presubmits.findLatest(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no presubmit round for " + ticketNo));
        var reviewRow = reviewResults.findLatestForPresubmit(presubmitRow.id())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no review result for " + ticketNo + " round " + presubmitRow.reviewRound()));
        byte[] findingsBytes = blobStore.get(new BlobRef(reviewRow.findingsBlobPath(), 0, "0".repeat(64)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("review_round", presubmitRow.reviewRound());
        body.put("verdict", reviewRow.verdict().name());
        body.put("engine_id", reviewRow.engine().engineId());
        body.put("covered_ok", reviewRow.coveredOk());
        body.put("degraded", reviewRow.degraded());
        body.put("findings", new String(findingsBytes, StandardCharsets.UTF_8));
        // 判决理由与其结构化依据只落在审计日志；这里从审计回读，让 Findings 页的
        // 判决卡片能"解释自己"（coverage gap / diff 超限 / 引擎故障……）。旧工单可能
        // 没有对应审计行，此时省略字段，前端回退到静态文案。
        if (auditLog instanceof gate.adapters.audit.HashChainAuditLog chain) {
            new gate.web.service.AuditReader(chain)
                    .latestReviewDecision(ticketNo, presubmitRow.reviewRound())
                    .ifPresent(d -> {
                        body.put("reason", d.get("reason"));
                        body.put("detail", d.get("detail"));
                    });
        }
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void presubmitList(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        if (tickets.find(ticketNo).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : presubmits.findAllByTicket(ticketNo)) {
            String diff = new String(blobStore.get(
                    new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256())),
                    StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("review_round", row.reviewRound());
            m.put("tree_hash", row.treeHash().hex());
            m.put("base_commit", row.baseCommit().hex());
            m.put("target_ref", row.targetRef());
            m.put("diff_bytes", row.diffBytes());
            m.put("changed_count", countOccurrences(diff, "diff --git a/"));
            m.put("created_at", row.createdAt().toString());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("presubmits", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void presubmitDiff(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        String roundStr = ctx.pathParam("round");
        int round = parseIntOr(roundStr, -1);
        var row = (round < 0 ? presubmits.findLatest(ticketNo) : presubmits.find(ticketNo, round))
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no presubmit round for " + ticketNo + (round < 0 ? "" : "/" + round)));
        byte[] diff = blobStore.get(new BlobRef(row.diffBlobPath(), row.diffBytes(), row.diffSha256()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("review_round", row.reviewRound());
        body.put("tree_hash", row.treeHash().hex());
        body.put("base_commit", row.baseCommit().hex());
        body.put("diff", new String(diff, StandardCharsets.UTF_8));
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static void appendNewFileDiff(StringBuilder out, Path clone, String rel) {
        Path file = clone.resolve(rel);
        out.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n');
        out.append("new file mode 100644\n");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            out.append("--- unreadable\n");
            return;
        }
        boolean binary = false;
        for (byte b : bytes.length > 8192 ? java.util.Arrays.copyOf(bytes, 8192) : bytes) {
            if (b == 0) {
                binary = true;
                break;
            }
        }
        if (binary) {
            out.append("Binary file ").append(rel).append(" differs\n");
            return;
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        }
        out.append("--- /dev/null\n+++ b/").append(rel).append('\n');
        out.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            out.append('+').append(line).append('\n');
        }
    }

    private static int parseIntOr(String str, int defaultVal) {
        if (str == null || str.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}