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
    /** 行尾噪声检测的行数门槛（原全量 diff 10 万字符 ≈ 2000 行级别变更），避免为告警拉全量 diff。 */
    private static final int EOL_NOISE_MIN_CHANGED_LINES = 2_000;
    /** 未跟踪文件行数计数的体积上限：超过则按 0 行计（内容仍可经 /diff/file 按需查看）。 */
    private static final long UNTRACKED_COUNT_LIMIT_BYTES = 4L * 1024 * 1024;
    private static final String EOL_WARNING_TEXT = "已忽略大量仅换行符（CRLF/LF）差异：该 clone 的检出未在 core.autocrlf=false 下进行，"
            + "建议重建工作区；以下仅显示真实的内容变更。";
    private static final java.util.regex.Pattern INSERTIONS = java.util.regex.Pattern.compile("(\\d+) insertions?");
    private static final java.util.regex.Pattern DELETIONS = java.util.regex.Pattern.compile("(\\d+) deletions?");

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
        app.get("/api/tickets/{ticketNo}/diff/list", this::workingDiffList);
        app.get("/api/tickets/{ticketNo}/diff/file", this::workingDiffFile);
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
        Ticket t = requireTicket(ticketNo);
        Path clone = requireClone(t);
        String baseCommit = baseCommitOf(ticketNo);
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

    /**
     * 变更列表端点（变更对比 tab 的默认数据源）：只返回 path / 状态 / 增删行数，不携带 diff 内容——
     * 内容按需经 {@link #workingDiffFile} 单文件拉取。会话期间前端会以亚秒节奏反复刷新本端点，
     * 因此必须保持 O(变更文件数) 而非 O(diff 字节) 的输出规模。
     */
    public void workingDiffList(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        Ticket t = requireTicket(ticketNo);
        Path clone = requireClone(t);
        String baseCommit = baseCommitOf(ticketNo);
        boolean diffHead = baseCommit != null || t.isSuper();

        // --no-renames：与全量 diff 的解析口径一致（重命名呈现为「删除 + 新增」两个条目）。
        Map<String, String> status = new LinkedHashMap<>();
        var ns = git.run(clone, Map.of(), trackedDiffArgs(diffHead, "--name-status", "-z").toArray(new String[0]));
        if (ns.ok()) {
            parseNameStatus(ns.stdout(), status);
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        var num = git.run(clone, Map.of(), trackedDiffArgs(diffHead, "--numstat", "-z").toArray(new String[0]));
        if (num.ok()) {
            parseNumstat(num.stdout(), counts);
        }

        // 行尾噪声检测降到 shortstat 粒度（输出恒为两行内），不再为告警物化全量 diff。
        String eolWarning = null;
        boolean ignoreCrAtEol = false;
        long[] raw = shortstat(clone, diffHead, false);
        if (raw[0] + raw[1] > EOL_NOISE_MIN_CHANGED_LINES) {
            long[] normalized = shortstat(clone, diffHead, true);
            if (normalized[0] * 10 < raw[0]) {
                eolWarning = EOL_WARNING_TEXT;
                ignoreCrAtEol = true;
            }
        }

        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", e.getKey());
            m.put("status", status.getOrDefault(e.getKey(), "modified"));
            m.put("additions", e.getValue()[0]);
            m.put("deletions", e.getValue()[1]);
            files.add(m);
        }
        var untracked = git.run(clone, Map.of(), "ls-files", "--others", "--exclude-standard", "-z");
        if (untracked.ok()) {
            for (String rel : untracked.stdout().split("\0", -1)) {
                if (rel.isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", rel);
                m.put("status", "added");
                m.put("additions", countLines(clone.resolve(rel)));
                m.put("deletions", 0);
                files.add(m);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("source", "working");
        body.put("base_commit", baseCommit);
        body.put("files", files);
        if (eolWarning != null) {
            body.put("eol_warning", eolWarning);
            body.put("ignore_cr_at_eol", ignoreCrAtEol);
        }
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /** 单文件 diff 端点：变更对比点击文件条时按需拉取该文件的 unified diff（?path=…，&eol=1 启用行尾归一化）。 */
    public void workingDiffFile(Context ctx) {
        String ticketNo = ctx.pathParam("ticketNo");
        String path = sanitizeDiffPath(ctx.queryParam("path"));
        Ticket t = requireTicket(ticketNo);
        Path clone = requireClone(t);
        String baseCommit = baseCommitOf(ticketNo);
        boolean diffHead = baseCommit != null || t.isSuper();
        boolean ignoreEol = "1".equals(ctx.queryParam("eol"));

        String diff;
        String eolWarning = null;
        // 未跟踪文件不在 git diff 输出里，单独物化 new-file diff（与全量端点 appendNewFileDiff 同口径）。
        var untracked = git.run(clone, Map.of(), "ls-files", "--others", "--exclude-standard", "-z", "--", path);
        if (untracked.ok() && !untracked.stdout().isBlank()) {
            StringBuilder sb = new StringBuilder();
            appendNewFileDiff(sb, clone, path);
            diff = sb.toString();
        } else {
            List<String> args = trackedDiffArgs(diffHead);
            if (ignoreEol) {
                args.add("--ignore-cr-at-eol");
            }
            args.add("--");
            args.add(path);
            var run = git.run(clone, Map.of(), args.toArray(new String[0]));
            diff = run.ok() ? run.stdout() : "";
            if (!ignoreEol && diff.length() > EOL_NOISE_THRESHOLD_CHARS) {
                // 单文件粒度的行尾噪声兜底，与全量端点同规则（归一化后缩到 1/10 才替换）。
                List<String> normArgs = trackedDiffArgs(diffHead, "--ignore-cr-at-eol", "--");
                normArgs.add(path);
                var normalized = git.run(clone, Map.of(), normArgs.toArray(new String[0]));
                if (normalized.ok() && normalized.stdout().length() * 10 < diff.length()) {
                    diff = normalized.stdout();
                    eolWarning = EOL_WARNING_TEXT;
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket_no", ticketNo);
        body.put("path", path);
        body.put("diff", diff);
        if (eolWarning != null) {
            body.put("eol_warning", eolWarning);
        }
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private Ticket requireTicket(String ticketNo) {
        return tickets.find(ticketNo).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such ticket: " + ticketNo));
    }

    private Path requireClone(Ticket t) {
        Path clone = Path.of(t.clonePath());
        if (!Files.isDirectory(clone)) {
            throw new GateException(GateErrorCode.USAGE,
                    "clone directory does not exist for " + t.ticketNo() + ": " + clone);
        }
        return clone;
    }

    private String baseCommitOf(String ticketNo) {
        return presubmits.findLatest(ticketNo).map(p -> p.baseCommit().hex()).orElse(null);
    }

    /** git diff 参数骨架（含 --no-renames，保证与列表口径一致）；extra 追加在末尾。 */
    private List<String> trackedDiffArgs(boolean diffHead, String... extra) {
        List<String> args = new ArrayList<>(List.of("diff"));
        if (diffHead) {
            args.add("HEAD");
        }
        args.add("--no-renames");
        args.addAll(List.of(extra));
        return args;
    }

    /** shortstat → [insertions, deletions]；无变更或解析失败返回 {0,0}。 */
    private long[] shortstat(Path clone, boolean diffHead, boolean ignoreCrAtEol) {
        List<String> args = new ArrayList<>(List.of("diff"));
        if (diffHead) {
            args.add("HEAD");
        }
        if (ignoreCrAtEol) {
            args.add("--ignore-cr-at-eol");
        }
        args.add("--shortstat");
        var run = git.run(clone, Map.of(), args.toArray(new String[0]));
        if (!run.ok()) {
            return new long[]{0, 0};
        }
        long ins = 0;
        long del = 0;
        var m = INSERTIONS.matcher(run.stdout());
        if (m.find()) {
            ins = Long.parseLong(m.group(1));
        }
        var m2 = DELETIONS.matcher(run.stdout());
        if (m2.find()) {
            del = Long.parseLong(m2.group(1));
        }
        return new long[]{ins, del};
    }

    /** name-status -z 输出（status\0path\0 交替）→ path → 状态（A→added，D→deleted，其余→modified）。 */
    private static void parseNameStatus(String out, Map<String, String> status) {
        String[] tok = out.split("\0", -1);
        for (int i = 0; i + 1 < tok.length; i += 2) {
            String p = tok[i + 1];
            if (p.isBlank()) {
                continue;
            }
            status.put(p, switch (tok[i].trim()) {
                case "A" -> "added";
                case "D" -> "deleted";
                default -> "modified";
            });
        }
    }

    /** numstat -z 输出（add\tdel\tpath\0 记录）→ path → [additions, deletions]；二进制（-）计 0。 */
    private static void parseNumstat(String out, Map<String, long[]> counts) {
        for (String rec : out.split("\0", -1)) {
            if (rec.isBlank()) {
                continue;
            }
            String[] parts = rec.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            counts.put(parts[2], new long[]{parseLongOr(parts[0]), parseLongOr(parts[1])});
        }
    }

    private static long parseLongOr(String s) {
        if ("-".equals(s)) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 未跟踪文件行数（即新增行数）：二进制文件与超过 {@link #UNTRACKED_COUNT_LIMIT_BYTES} 的文件
     * 不逐行计数（返回 0），列表刷新不必反复读大文件；内容仍可在 /diff/file 按需物化。
     */
    private static long countLines(Path file) {
        try {
            if (Files.size(file) > UNTRACKED_COUNT_LIMIT_BYTES) {
                return 0;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
                if (bytes[i] == 0) {
                    return 0;
                }
            }
            long n = 0;
            for (byte b : bytes) {
                if (b == '\n') {
                    n++;
                }
            }
            return n > 0 && bytes[bytes.length - 1] != '\n' ? n + 1 : n;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String sanitizeDiffPath(String raw) {
        String path = raw == null ? "" : raw.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank() || path.contains("..")) {
            throw new GateException(GateErrorCode.USAGE, "invalid path for single-file diff: " + raw);
        }
        return path;
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