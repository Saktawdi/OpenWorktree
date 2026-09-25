package gate.adapters.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.domain.blob.BlobRef;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.policy.Decision;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.Policy;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.SkippedPath;
import gate.domain.snapshot.Snapshot;
import gate.ports.engine.ReviewEngine;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查质量基准集回归（P2-4，反哺 OCR 的 AACR-Bench 思路）：src/test/resources/golden/ 下每个
 * JSON fixture 是一个端到端场景——diff + 脚本化 LLM 响应 + 配置 → 期望的判决/发现/跳过台账/
 * 过滤结果/prompt 内容。跑的是真实的 BuiltinReviewEngine + GatePolicy 全链路，只把上游 LLM
 * 换成脚本。改 prompt、改规则解析、改策略之后跑这套：防止「优化一个误报、劣化三个漏报」的
 * 静默回归。扩充基准集 = 往 golden/ 丢一个新 fixture 文件，无需写代码。
 */
class GoldenReviewSetTest {

    @TestFactory
    Stream<DynamicTest> goldenScenarios() throws Exception {
        Path dir = Path.of(getClass().getResource("/golden").toURI());
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> fixtures = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            return fixtures.stream().map(p -> DynamicTest.dynamicTest(
                    p.getFileName().toString(), () -> runScenario(p)));
        }
    }

    private void runScenario(Path fixturePath) throws Exception {
        Map<String, Object> fx = PrismJson.parseObjectMap(Files.readString(fixturePath, StandardCharsets.UTF_8));
        String name = str(fx, "name");
        String diff = str(fx, "diff");
        List<String> changedPaths = strings(fx.get("changed_paths"));
        List<String> responses = strings(fx.get("responses"));

        // 可选项目规则：写入临时克隆目录（与引擎读取路径一致）
        Path clone = null;
        if (fx.get("rules") instanceof Map<?, ?> rulesObj) {
            clone = Files.createTempDirectory("golden-rules");
            Files.createDirectories(clone.resolve(".gate"));
            Files.writeString(clone.resolve(".gate").resolve("rules.json"), json(rulesObj),
                    StandardCharsets.UTF_8);
        }

        StubLlm stub = new StubLlm(responses);
        stub.start();

        Boolean filter = fx.get("filter_enabled") instanceof Boolean b ? b : null;
        Integer rounds = fx.get("rounds") instanceof Number n ? n.intValue() : null;
        Long maxFileTokens = fx.get("max_file_tokens") instanceof Number n ? n.longValue() : null;

        MemBlobs blobs = new MemBlobs();
        BuiltinReviewEngine engine = new BuiltinReviewEngine(blobs, Duration.ofSeconds(10),
                Duration.ofSeconds(5), "temp", "test-model",
                "http://127.0.0.1:" + stub.port() + "/v1", "sk-test", null,
                filter, rounds, null, maxFileTokens, null);

        Snapshot snap = new Snapshot(ObjectId.of("a".repeat(40)), ObjectId.of("b".repeat(40)),
                ObjectId.of("c".repeat(40)), "refs/heads/main", changedPaths, diff,
                gate.domain.snapshot.CaptureIntegrityReport.clean());
        ReviewEngine.ReviewRequest req = new ReviewEngine.ReviewRequest(
                clone == null ? null : RepoRef.of(clone), "T-9000", 3, snap, ObjectId.of("d".repeat(40)));

        try {
            ReviewEvidence evidence = engine.review(req);

            Map<String, Object> expected = cast(fx.get("expected"));
            if ("failure".equals(str(expected, "outcome"))) {
                EngineFailure failure = (EngineFailure) evidence;
                assertEquals(str(expected, "failure_kind"), failure.kind().name(), name + ": 失败类别");
                if (expected.containsKey("verdict")) {
                    Decision d = new GatePolicy().decide("T-9000", 3, evidence, snap, Policy.defaults());
                    assertEquals(str(expected, "verdict"), d.verdict().name(), name + ": 判决");
                }
                return;
            }

            if (evidence instanceof EngineFailure unexpected) {
                throw new AssertionError(name + " expected report but got "
                        + unexpected.kind() + " :: " + unexpected.detail());
            }
            EngineReport report = (EngineReport) evidence;
            assertEquals(str(expected, "verdict"),
                    new GatePolicy().decide("T-9000", 3, evidence, snap, Policy.defaults())
                            .verdict().name(), name + ": 判决");
            if (expected.containsKey("finding_count")) {
                assertEquals(((Number) expected.get("finding_count")).intValue(),
                        report.findings().size(), name + ": 发现数");
            }
            if (expected.containsKey("finding_severities")) {
                List<String> severities = report.findings().stream().map(f -> f.severity().name()).toList();
                assertEquals(strings(expected.get("finding_severities")), severities, name + ": severity 序列");
            }
            if (expected.containsKey("filtered_count")) {
                assertEquals(((Number) expected.get("filtered_count")).intValue(),
                        report.filteredFindings().size(), name + ": 被过滤数");
            }
            if (expected.get("skipped") instanceof List<?> skipList) {
                for (Object o : skipList) {
                    Map<?, ?> sp = (Map<?, ?>) o;
                    assertTrue(report.skippedPaths().contains(new SkippedPath(
                            String.valueOf(sp.get("path")), String.valueOf(sp.get("reason")))),
                            name + ": 缺少 skip 台账 " + sp);
                }
            }
            if (expected.get("prompt_contains") instanceof List<?> mustContain) {
                String allBodies = String.join("\n", stub.requestBodies);
                for (Object o : mustContain) {
                    assertTrue(allBodies.contains(String.valueOf(o)),
                            name + ": prompt 应包含「" + o + "」");
                }
            }
            if (expected.containsKey("request_count")) {
                assertEquals(((Number) expected.get("request_count")).intValue(),
                        stub.requestBodies.size(), name + ": LLM 调用次数");
            }
        } finally {
            stub.stop();
            if (clone != null) {
                deleteRecursively(clone);
            }
        }
    }

    // ────────────────────────────── 脚本化 LLM stub 与基建 ──────────────────────────────

    /** 第 N 个请求拿第 N 个响应，越界重复最后一个（过滤/宽限轮 fail-open 路径天然兼容）。 */
    private static final class StubLlm {
        private final List<String> responses;
        private final List<String> requestBodies = new ArrayList<>();
        private HttpServer server;
        private int cursor = 0;

        StubLlm(List<String> responses) {
            this.responses = responses;
        }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", ex -> {
                try {
                    requestBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    int i = Math.min(cursor, responses.size() - 1);
                    cursor++;
                    sse(ex, responses.get(i));
                    ex.close();
                } catch (IOException ignored) {
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "golden-stub");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private static void sse(HttpExchange ex, String content) throws IOException {
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            String chunk = "{\"choices\":[{\"delta\":{\"content\":" + json(content) + "}}]}";
            out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static final class MemBlobs implements gate.ports.store.BlobStore {
        final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public BlobRef put(byte[] data, String relPath) {
            store.put(relPath, data.clone());
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(data)) {
                    sb.append(String.format("%02x", b));
                }
                return new BlobRef(relPath, data.length, sb.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] get(BlobRef ref) {
            byte[] d = store.get(ref.relPath());
            if (d == null) {
                throw new IllegalArgumentException("no blob at " + ref.relPath());
            }
            return d;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static String str(Map<String, Object> m, String key) {
        return String.valueOf(m.get(key));
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }

    /** 极简对象序列化（仅测试夹具规则对象用）：委托给 PrismJson 不合适——手写足够。 */
    private static String json(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(json(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(json(e));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Boolean || o instanceof Number) {
            return String.valueOf(o);
        }
        return "\"" + String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
