package gate.application.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineReport;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.review.SkippedPath;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 证据 blob 的确定性序列化/反序列化：publish 在自己的进程里重跑 GatePolicy，
 * skipped_paths 必须原样往返——丢一个 skip 就会把授权排除误判成覆盖缺口。
 */
class EvidenceCodecTest {

    private static final String SHA = "a".repeat(64);

    private static final EngineDescriptor DESC = new EngineDescriptor("gate-engine", "v2", "t", "p", "m");

    @Test
    void reportRoundTripsSkippedFilteredAndExistingCode() {
        BlobRef raw = new BlobRef("raw/T-1/2/builtin.json", 10, SHA);
        EngineReport report = new EngineReport(
                DESC, "tree", List.of(new Finding(Severity.BLOCKER, "high", "src/A.java", 12, 14,
                        "f1", "msg", "fix", "PreparedStatement ps = conn.prepareStatement(sql);")),
                new LinkedHashSet<>(Set.of("src/A.java")),
                List.of(new SkippedPath(".env", SkippedPath.SECRET_PATH),
                        new SkippedPath("big/B.java", SkippedPath.TOO_LARGE)),
                false, raw, 0, Duration.ofMillis(5), 100L, 20L, 120L,
                List.of(new Finding(Severity.INFO, "low", "src/A.java", 1, 1, "f2", "removed", null, "ghost")));

        ReviewEvidence hydrated = EvidenceCodec.fromJson(EvidenceCodec.toJson(report), raw);
        EngineReport back = (EngineReport) hydrated;

        assertEquals(report.findings(), back.findings(), "existing_code 随发现往返");
        assertEquals(report.skippedPaths(), back.skippedPaths(), "skip 台账必须原样往返");
        assertEquals(report.filteredFindings(), back.filteredFindings());
        assertEquals(report.coveredPaths(), back.coveredPaths());
        // token 遥测是 P4 bypass 数据，不参与判决——hydrate 恒 null 是既有契约（blob 文本里仍在）。
        org.junit.jupiter.api.Assertions.assertNull(back.totalTokens());
    }

    @Test
    void hydratedSkipsKeepPolicyVerdictStable() {
        BlobRef raw = new BlobRef("raw/T-1/1/builtin.json", 10, SHA);
        EngineReport report = new EngineReport(DESC, "tree", List.of(),
                Set.of(), List.of(new SkippedPath(".env", SkippedPath.SECRET_PATH)),
                false, raw, 0, Duration.ZERO, null, null, null, List.of());
        EngineReport back = (EngineReport) EvidenceCodec.fromJson(EvidenceCodec.toJson(report), raw);
        assertTrue(back.skippedPaths().contains(new SkippedPath(".env", SkippedPath.SECRET_PATH)));
    }

    @Test
    void legacyBlobWithoutNewFieldsStillHydrates() {
        // 0.3.15 时代的 evidence.json：没有 skipped_paths / filtered_findings / existing_code。
        String legacy = "{\"kind\":\"report\",\"engine_id\":\"gate-engine\",\"engine_version\":\"v1\","
                + "\"provider_id\":\"p\",\"model_name\":\"m\",\"tree_hash\":\"tree\",\"degraded\":false,"
                + "\"exit_code\":0,\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3,"
                + "\"covered_paths\":[\"src/A.java\"],"
                + "\"findings\":[{\"severity\":\"BLOCKER\",\"raw_severity\":\"high\",\"path\":\"src/A.java\","
                + "\"line_start\":1,\"line_end\":2,\"rule_id\":\"f1\",\"message\":\"m\",\"suggestion\":null}]}";
        EngineReport back = (EngineReport) EvidenceCodec.fromJson(legacy,
                new BlobRef("raw/x", 1, SHA));
        assertEquals(1, back.findings().size());
        assertEquals(List.of(), back.skippedPaths(), "旧 blob 无 skip 字段 → 空台账");
        assertEquals(List.of(), back.filteredFindings());
    }
}
