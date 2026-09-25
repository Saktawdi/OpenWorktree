package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineFailure;
import gate.domain.review.Finding;
import gate.domain.review.Severity;
import gate.ports.store.BlobStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审查会话逐请求留痕（P2-1，反哺 OCR 的 session JSONL）：一次 review 的每一次 LLM 调用
 * ——system/user prompt 原文、原始响应、usage、耗时、阶段（main/grace/filter）、组号与轮次
 * ——逐行落一个 JSONL blob（{@code sessions/{ticket}/{round}.jsonl}）。hash-chain 审计记录
 * 的是「判决与依据」，这里记录的是「过程本身」：排查一次误判时能看到每一轮 prompt 原文，
 * 而不是只看到结论。
 *
 * <p>同时它是 P2-2 续审（resume）的 resume 索引：每条 {@code group_result} 携带组指纹
 * （组 diff 内容的 sha256）与完整发现列表。重试同一轮时，引擎读取上一次尝试的日志——
 * 仅当上一次以<b>失败</b>告终且模型一致——把指纹相同且 status=completed 的组的发现原样
 * 复用，只重新派发失败/缺失的组。成功的审查不产生复用：显式重审同一轮就是要求全新审查。
 *
 * <p>留痕是诊断数据不是安全控制：写失败 best-effort 吞掉（P4 bypass 同款），绝不影响判决；
 * blob 落盘的内容与既有 raw blob 同级（密钥文件本就在审前闸门被排除，prompt 不含其内容）。
 */
final class ReviewSessionLog {

    static final String LOG_REL_PATH_FMT = "sessions/%s/%d.jsonl";

    private final List<String> lines = new ArrayList<>();

    // ────────────────────────────── 写侧 ──────────────────────────────

    void header(String ticketNo, int round, String treeHash, String providerId, String modelName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "header");
        m.put("ticket", ticketNo);
        m.put("round", round);
        m.put("tree", treeHash);
        m.put("provider", providerId);
        m.put("model", modelName);
        add(m);
    }

    void llmCall(String phase, int group, int round, String systemPrompt, String userPrompt,
                 String responseContent, Long promptTokens, Long completionTokens, Long totalTokens,
                 long durationMs, BlobRef rawRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "llm_call");
        m.put("phase", phase);
        m.put("group", group);
        m.put("round", round);
        m.put("system_prompt", systemPrompt);
        m.put("user_prompt", userPrompt);
        m.put("response", responseContent);
        if (promptTokens != null) {
            m.put("prompt_tokens", promptTokens);
        }
        if (completionTokens != null) {
            m.put("completion_tokens", completionTokens);
        }
        if (totalTokens != null) {
            m.put("total_tokens", totalTokens);
        }
        m.put("duration_ms", durationMs);
        if (rawRef != null) {
            m.put("raw_blob", rawRef.relPath());
        }
        add(m);
    }

    /** 一组的终局：completed（含指纹与完整发现，供续审复用）或 failed。 */
    void groupResult(int group, String fingerprint, String status,
                     List<Finding> findings, String failureKind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "group_result");
        m.put("group", group);
        m.put("group_fingerprint", fingerprint);
        m.put("status", status);
        if (findings != null) {
            m.put("findings", findingsJson(findings));
        }
        if (failureKind != null) {
            m.put("failure_kind", failureKind);
        }
        add(m);
    }

    void groupReused(int group, String fingerprint, List<Finding> findings, String fromModel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "group_reused");
        m.put("group", group);
        m.put("group_fingerprint", fingerprint);
        m.put("findings", findingsJson(findings));
        m.put("model", fromModel);
        add(m);
    }

    void terminalReport(int reusedGroups, int dispatchedGroups, int findingCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "terminal");
        m.put("outcome", "report");
        m.put("reused_groups", reusedGroups);
        m.put("dispatched_groups", dispatchedGroups);
        m.put("findings", findingCount);
        add(m);
    }

    void terminalFailure(EngineFailure.FailureKind kind, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "terminal");
        m.put("outcome", "failure");
        m.put("failure_kind", kind.name());
        m.put("detail", detail);
        add(m);
    }

    /** best-effort：落盘失败只吞掉——诊断留痕绝不能让审查本身失败。 */
    void write(BlobStore blobStore, String ticketNo, int round) {
        if (blobStore == null || lines.isEmpty()) {
            return;
        }
        try {
            byte[] data = String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8);
            blobStore.put(data, String.format(LOG_REL_PATH_FMT, ticketNo, round));
        } catch (RuntimeException ignored) {
            // 留痕失败不影响审查结果（P4 bypass 同款语义）
        }
    }

    // ────────────────────────────── 读侧（resume 索引） ──────────────────────────────

    /** 上一次尝试是否以失败告终——只有失败的尝试才允许被续审复用。 */
    boolean wasFailedAttempt() {
        for (Map<String, Object> line : parseAll()) {
            if ("terminal".equals(line.get("kind")) && "failure".equals(line.get("outcome"))) {
                return true;
            }
        }
        return false;
    }

    String modelName() {
        for (Map<String, Object> line : parseAll()) {
            if ("header".equals(line.get("kind"))) {
                Object m = line.get("model");
                return m == null ? null : String.valueOf(m);
            }
        }
        return null;
    }

    /** 指纹 → status=completed 组的发现（按组号去重，首次为准）。 */
    Map<String, List<Finding>> completedGroupsByFingerprint() {
        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (Map<String, Object> line : parseAll()) {
            if (!"group_result".equals(line.get("kind")) || !"completed".equals(line.get("status"))) {
                continue;
            }
            Object fpObj = line.get("group_fingerprint");
            String fp = fpObj == null ? null : String.valueOf(fpObj);
            if (fp == null || out.containsKey(fp)) {
                continue;
            }
            out.put(fp, findingsFrom(line.get("findings")));
        }
        return out;
    }

    // ────────────────────────────── 解析与编码 ──────────────────────────────

    private List<Map<String, Object>> parsed;

    private List<Map<String, Object>> parseAll() {
        if (parsed != null) {
            return parsed;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : lines) {
            try {
                out.add(PrismJson.parseObjectMap(line));
            } catch (RuntimeException ignored) {
                // 坏行跳过：日志是诊断数据，解析失败不影响其余行
            }
        }
        parsed = out;
        return out;
    }

    /** 从 blob 读取上一次尝试的日志；不存在或解析失败得到空日志（无可复用内容）。 */
    static ReviewSessionLog read(BlobStore blobStore, String ticketNo, int round) {
        ReviewSessionLog log = new ReviewSessionLog();
        if (blobStore == null) {
            return log;
        }
        try {
            byte[] data = blobStore.get(new BlobRef(
                    String.format(LOG_REL_PATH_FMT, ticketNo, round), 0, "0".repeat(64)));
            for (String line : new String(data, StandardCharsets.UTF_8).split("\n", -1)) {
                if (!line.isBlank()) {
                    log.lines.add(line);
                }
            }
        } catch (RuntimeException ignored) {
            // 首次审查（无历史日志）或读取失败：等价于无可复用内容
        }
        return log;
    }

    private void add(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        sb.append('}');
        lines.add(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(value(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?>) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(value(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return quote(String.valueOf(v));
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }

    private static List<Object> findingsJson(List<Finding> findings) {
        List<Object> out = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity().name());
            m.put("raw_severity", f.rawSeverity());
            m.put("path", f.path());
            if (f.lineStart() != null) {
                m.put("line_start", f.lineStart());
            }
            if (f.lineEnd() != null) {
                m.put("line_end", f.lineEnd());
            }
            if (f.ruleId() != null) {
                m.put("rule_id", f.ruleId());
            }
            m.put("message", f.message());
            if (f.suggestion() != null) {
                m.put("suggestion", f.suggestion());
            }
            if (f.existingCode() != null) {
                m.put("existing_code", f.existingCode());
            }
            out.add(m);
        }
        return out;
    }

    private static List<Finding> findingsFrom(Object raw) {
        List<Finding> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            try {
                out.add(new Finding(
                        Severity.valueOf(String.valueOf(m.get("severity"))),
                        strOr(m, "raw_severity", ""),
                        String.valueOf(m.get("path")),
                        intOrNull(m, "line_start"),
                        intOrNull(m, "line_end"),
                        strOrNull(m, "rule_id"),
                        strOr(m, "message", ""),
                        strOrNull(m, "suggestion"),
                        strOrNull(m, "existing_code")));
            } catch (RuntimeException ignored) {
                // 单条坏发现跳过，不拖垮整组复用
            }
        }
        return out;
    }

    private static String strOr(Map<?, ?> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static String strOrNull(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /** 组指纹：组 diff 全文的 sha256——diff 内容变即指纹变，续审绝不会复用过期发现。 */
    static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
