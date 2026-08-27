package gate.application.metrics;
import gate.application.util.MiniJson;


import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serialises a {@link ReviewEvidence} to a small JSON blob for persistence and re-hydration.
 *
 * <p>{@code publish} re-runs {@code GatePolicy} in its own process, so it must be able to rebuild the
 * evidence from the stored blob. Keeping this deterministic and dependency-free (no Jackson) keeps
 * the blob stable across runs — the same evidence always serialises to the same bytes, which the
 * hash-chained audit log and the {@code diff_sha256} accounting rely on.
 */
public final class EvidenceCodec {

    private EvidenceCodec() {
    }

    public static String toJson(ReviewEvidence evidence) {
        return evidence.accept(new EvidenceVisitor<String>() {
            @Override
            public String visit(EngineReport report) {
                String findings = report.findings().stream()
                        .map(EvidenceCodec::findingJson)
                        .collect(Collectors.joining(","));
                String covered = report.coveredPaths().stream()
                        .sorted()
                        .map(EvidenceCodec::quote)
                        .collect(Collectors.joining(","));
                return "{\"kind\":\"report\""
                        + ",\"engine_id\":" + quote(report.engine().engineId())
                        + ",\"engine_version\":" + quote(report.engine().engineVersion())
                        + ",\"provider_id\":" + quote(report.engine().providerId())
                        + ",\"model_name\":" + quote(report.engine().modelName())
                        + ",\"tree_hash\":" + quote(report.treeHash())
                        + ",\"degraded\":" + report.degraded()
                        + ",\"exit_code\":" + report.exitCode()
                        + ",\"prompt_tokens\":" + nullableLong(report.promptTokens())
                        + ",\"completion_tokens\":" + nullableLong(report.completionTokens())
                        + ",\"total_tokens\":" + nullableLong(report.totalTokens())
                        + ",\"covered_paths\":[" + covered + "]"
                        + ",\"findings\":[" + findings + "]}";
            }

            @Override
            public String visit(gate.domain.review.EngineFailure failure) {
                return "{\"kind\":\"failure\""
                        + ",\"engine_id\":" + quote(failure.engine().engineId())
                        + ",\"failure_kind\":" + quote(failure.kind().name())
                        + ",\"detail\":" + quote(failure.detail())
                        + ",\"exit_code\":" + failure.exitCode() + "}";
            }
        });
    }

    private static String findingJson(Finding f) {
        return "{\"severity\":" + quote(f.severity().name())
                + ",\"raw_severity\":" + quote(f.rawSeverity())
                + ",\"path\":" + quote(f.path())
                + ",\"line_start\":" + (f.lineStart() == null ? "null" : f.lineStart())
                + ",\"line_end\":" + (f.lineEnd() == null ? "null" : f.lineEnd())
                + ",\"rule_id\":" + quote(f.ruleId())
                + ",\"message\":" + quote(f.message())
                + ",\"suggestion\":" + quote(f.suggestion()) + "}";
    }

    static Severity worstSeverity(List<Finding> findings) {
        Severity worst = null;
        for (Finding f : findings) {
            if (worst == null || f.severity().ordinal() < worst.ordinal()) {
                worst = f.severity();
            }
        }
        return worst;
    }

    /**
     * Rebuilds evidence from a stored blob so {@code publish} can re-run {@code GatePolicy} in its
     * own process. A failure blob deserialises back to an {@link EngineFailure}, which the policy
     * turns into a reject — so a corrupted or truncated blob can never re-hydrate into a pass.
     */
    public static ReviewEvidence fromJson(String json, BlobRef rawOutput) {
        Map<String, Object> root = MiniJson.parseObject(json);
        String kind = (String) root.get("kind");
        EngineDescriptor engine = new EngineDescriptor(
                str(root, "engine_id"),
                strOr(root, "engine_version", "unknown"),
                "",
                strOr(root, "provider_id", "manual"),
                strOr(root, "model_name", "human"));
        if ("failure".equals(kind)) {
            return new EngineFailure(engine,
                    EngineFailure.FailureKind.valueOf(str(root, "failure_kind")),
                    strOr(root, "detail", ""),
                    (int) longVal(root, "exit_code"));
        }
        if (!"report".equals(kind)) {
            return new EngineFailure(engine, EngineFailure.FailureKind.UNPARSEABLE,
                    "unknown evidence kind: " + kind, -1);
        }
        Set<String> covered = new LinkedHashSet<>();
        for (Object p : listOf(root.get("covered_paths"))) {
            covered.add((String) p);
        }
        List<Finding> findings = new ArrayList<>();
        for (Object f : listOf(root.get("findings"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) f;
            findings.add(new Finding(
                    Severity.valueOf(str(fm, "severity")),
                    strOr(fm, "raw_severity", ""),
                    str(fm, "path"),
                    intOrNull(fm, "line_start"),
                    intOrNull(fm, "line_end"),
                    strOrNull(fm, "rule_id"),
                    strOr(fm, "message", ""),
                    strOrNull(fm, "suggestion")));
        }
        return new EngineReport(engine, str(root, "tree_hash"), findings, covered,
                Boolean.TRUE.equals(root.get("degraded")), rawOutput,
                (int) longVal(root, "exit_code"), Duration.ZERO);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        return (String) v;
    }

    private static String strOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : (String) v;
    }

    private static String strOrNull(Map<String, Object> m, String key) {
        return (String) m.get(key);
    }

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? 0L : (Long) v;
    }

    private static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : (int) (long) (Long) v;
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 可为 null 的 Long 直接序列化（null 或数字）。 */
    private static String nullableLong(Long v) {
        return v == null ? "null" : v.toString();
    }
}
