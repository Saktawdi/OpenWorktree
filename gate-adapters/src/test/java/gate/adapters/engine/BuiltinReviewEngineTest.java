package gate.adapters.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.domain.git.ObjectId;
import gate.domain.blob.BlobRef;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.snapshot.Snapshot;
import gate.ports.session.CostHint;
import gate.ports.store.BlobStore;
import gate.ports.engine.ReviewEngine;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * gate-engine 的 stub SSE server 测试（执行文档 v2 §6）。
 *
 * <p>最重要的一条是<b>黑洞测试</b>：服务建立响应后一字节不发——这是看门狗存在的唯一可信证明
 * （v1 实现的 idle 判断写在 readLine 返回之后，对黑洞挂死根本执行不到；总超时也必须真实
 * 覆盖 body 阶段，因为 {@code HttpRequest.timeout()} 只管到响应头）。
 */
class BuiltinReviewEngineTest {

    private HttpServer server;
    private MemBlobStore blobs;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ────────────────────────────── 场景测试 ──────────────────────────────

    @Test
    void normalChunksWithUsageTailYieldFullReport() throws Exception {
        // 正文 delta 分两段送达，usage-only 帧（choices 空数组）收尾。
        start(ex -> {
            try {
                String f = findingsJson();
                sse(ex,
                        delta(f.substring(0, f.length() / 2)),
                        delta(f.substring(f.length() / 2)),
                        usageOnly(2400, 5792, 8192),
                        "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });

        ReviewEvidence evidence = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req());
        EngineReport report = assertInstanceOf(EngineReport.class, evidence);

        assertEquals(1, report.findings().size());
        assertEquals(Severity.BLOCKER, report.findings().get(0).severity(), "分段拼接后完整解析出 high");
        assertEquals("src/A.java", report.findings().get(0).path());
        assertTrue(report.coveredPaths().containsAll(List.of("src/A.java")), "覆盖诚实：covered==changed");
        assertFalse(report.degraded());
        assertEquals(0, report.exitCode());
        assertEquals(2400L, report.promptTokens());
        assertEquals(5792L, report.completionTokens());
        assertEquals(8192L, report.totalTokens(), "choices 为空数组的 usage 帧也要被采纳");

        CostHint cost = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null)
                .extractCost(evidence).orElseThrow();
        assertEquals("stream_usage", cost.tokenSource());
        assertTrue(cost.hasTokenData());

        assertTrue(report.rawOutput().relPath().endsWith("/builtin.json"), "blob 命名统一 builtin.json");
        String stored = new String(blobs.store.get(report.rawOutput().relPath()), StandardCharsets.UTF_8);
        assertTrue(stored.contains("\"findings\""), "blob 存的是原样未改动的完整输出（含围栏假如有）");
    }

    @Test
    void fencedJsonIsStrippedBeforeParse() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta("```json\n" + findingsJson() + "\n```"), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(1, report.findings().size(), "围栏包裹容忍性剥离");
        assertEquals("```json\n" + findingsJson() + "\n```",
                new String(blobs.store.get(report.rawOutput().relPath()), StandardCharsets.UTF_8),
                "blob 存未改的原始输出；围栏只参与字符串解析");
    }

    @Test
    void thinkPrefixedAnswerFromReasoningModelStillParses() throws Exception {
        // kimi-k3 类 thinking 模型：思维链以 <think>…</think> 内联进 content，真身 JSON 跟在闭合标记后；
        // 跨帧送达验证剥离发生在拼接后的完整 content 上，而非单帧。
        start(ex -> {
            try {
                String think = "<think>\nFind bugs. Weigh candidates one by one.\n</think>\n";
                sse(ex, delta(think.substring(0, think.length() / 2)),
                        delta(think.substring(think.length() / 2) + findingsJson()), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(1, report.findings().size(), "think 块剥离后正文 JSON 正常解析");
        assertTrue(new String(blobs.store.get(report.rawOutput().relPath()), StandardCharsets.UTF_8)
                .startsWith("<think>"), "blob 仍存原样输出，含完整思维链");
    }

    @Test
    void thinkInsideFenceStillParses() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta("```json\n<think>deliberate</think>\n" + findingsJson() + "\n```"), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(1, report.findings().size(), "围栏内嵌 think 块的两级剥离");
    }

    @Test
    void unclosedThinkBlockFailsClosedAsUnparseable() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta("<think>\nreasoning cut off before the answer"), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, failure.kind(),
                "未闭合 think 块 = 正文无 JSON，诚实失败而非空 findings");
    }

    @Test
    void streamWithoutUsageDegradesTelemetryOnly() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta(findingsJson()), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        ReviewEvidence evidence = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req());
        CostHint cost = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null)
                .extractCost(evidence).orElseThrow();
        assertInstanceOf(EngineReport.class, evidence);
        assertEquals("unavailable", cost.tokenSource());
        assertFalse(cost.hasTokenData());
        assertTrue(cost.reviewWallMs() >= 0, "墙钟仍是可用的降级基准");
    }

    @Test
    void malformedMidFrameIsSkippedNotFatal() throws Exception {
        start(ex -> {
            try {
                sse(ex,
                        "{\"choices\":[{\"delta\":{\"cont",   // 无法解析的噪声帧，parseObjectMap 抛出 → 跳过不计入 content
                        delta(findingsJson()),
                        "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(1, report.findings().size(), "坏帧跳过后正文完整");
    }

    @Test
    void unparseableFinalAnswerBecomesUnparseableFailure() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta("<<not valid json output>>"), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, failure.kind());
        assertTrue(failure.detail().contains("not valid json output"), failure.detail());
    }

    @Test
    void inlineErrorFrameInsideHttp200IsCrashWithUpstreamMessage() throws Exception {
        start(ex -> {
            try {
                sse(ex, "{\"error\":{\"message\":\"quota exceeded for model\",\"code\":429}}");
                Thread.sleep(Long.MAX_VALUE);   // 不再发数据也不关流——引擎应主动终止而非等超时
            } catch (IOException | InterruptedException ignored) {
            }
        });
        long t0 = System.nanoTime();
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofSeconds(30), Duration.ofSeconds(30), null).review(req()));
        assertEquals(EngineFailure.FailureKind.CRASH, failure.kind());
        assertTrue(failure.detail().contains("quota exceeded"), failure.detail());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 20_000,
                "识别内联 error 帧必须立即返回");
        CostHint cost = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).extractCost(failure).orElseThrow();
        assertEquals(CostHint.EMPTY.tokenSource(), cost.tokenSource(), "失败证据的成本恒 empty（bypass）");
    }

    @Test
    void eofWithoutDoneSentinelStillCompletes() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta(findingsJson()));
                ex.close();   // EOF 收尾：部分网关不给 [DONE] 哨兵
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(1, report.findings().size());
    }

    /** ★ 黑洞测试：响应头发出后一字节不给——看门狗必须在 idle 窗口内判 TIMEOUT。 */
    @Test
    void blackHoleStreamFiresIdleWatchdogInsteadOfHangingForever() throws Exception {
        start(blackHoleHandler());
        long t0 = System.nanoTime();
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofSeconds(60), Duration.ofMillis(600), null).review(req()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);

        assertEquals(EngineFailure.FailureKind.TIMEOUT, failure.kind(),
                "黑洞停流必须被判 TIMEOUT 而非永久悬挂在 readLine");
        assertTrue(failure.detail().startsWith("idle"), failure.detail());
        assertTrue(elapsed.toMillis() < 15_000, "elapsed=" + elapsed + "，必须远小于总超时窗口");
    }

    /** 总超时真实覆盖 body 阶段：total < idle 时按总窗口触发。 */
    @Test
    void totalTimeoutEnforcedDuringBodyPhaseWhenShorterThanIdle() throws Exception {
        start(blackHoleHandler());
        long t0 = System.nanoTime();
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofMillis(400), Duration.ofSeconds(30), null).review(req()));

        assertEquals(EngineFailure.FailureKind.TIMEOUT, failure.kind());
        assertTrue(failure.detail().startsWith("total"), failure.detail());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() < 25_000);
    }

    @Test
    void unknownSeverityWordSinksToBlockerAndMarksDegraded() throws Exception {
        start(ex -> {
            try {
                sse(ex, delta("{\"findings\":[{\"id\":\"f9\",\"severity\":\"critical\","
                        + "\"title\":\"severe\",\"message\":\"m\",\"locations\":[]}]}"),
                        "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
        assertEquals(Severity.BLOCKER, report.findings().get(0).severity());
        assertEquals("critical", report.findings().get(0).rawSeverity(), "原始 severity 词保留在 finding 上");
        assertTrue(report.degraded(), "未知 severity 词 → BLOCKER 沉降 + degraded 标记");
    }

    @Test
    void maxTokensInjectedIntoRequestBodyOnlyWhenConfigured() throws Exception {
        final StringBuilder captured = new StringBuilder();
        start(ex -> {
            try {
                captured.append(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                sse(ex, delta(findingsJson()), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });

        engine(Duration.ofSeconds(10), Duration.ofSeconds(5), 512L).review(req());
        assertTrue(captured.toString().contains("\"max_tokens\":512"), captured.toString());

        captured.setLength(0);
        engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req());
        assertFalse(captured.toString().contains("\"max_tokens\""), "未配置则不注入该字段");
    }

    @Test
    void preCancelledThreadAbortsBeforeAnyUpstreamCall() throws IOException {
        start(blackHoleHandler());   // 起服务器仅为构造 URL；早退路径不应打到它
        Thread.currentThread().interrupt();
        try {
            EngineFailure failure = assertInstanceOf(EngineFailure.class,
                    engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null).review(req()));
            assertEquals(EngineFailure.FailureKind.CRASH, failure.kind());
            assertTrue(failure.detail().startsWith("aborted by task cancellation"), failure.detail());
        } finally {
            Thread.interrupted();   // 清理标记，避免污染其他测试线程状态
        }
    }

    // ────────────────────────────── 测试基建 ──────────────────────────────

    private static com.sun.net.httpserver.HttpHandler blackHoleHandler() {
        return ex -> {
            try {
                ex.sendResponseHeaders(200, 0);   // 立即给出 chunked 响应头，然后永久静默
                Thread.sleep(Long.MAX_VALUE);
            } catch (IOException | InterruptedException ignored) {
            }
        };
    }

    private BuiltinReviewEngine engine(Duration total, Duration idle, Long maxTokens) {
        if (blobs == null) {
            blobs = new MemBlobStore();   // 同一用例内多次构造引擎共享 store：extractCost 不读 store
        }
        return new BuiltinReviewEngine(blobs, total, idle, "temp", "test-model",
                "http://127.0.0.1:" + port() + "/v1", "sk-test", maxTokens);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        // daemon 线程池：部分 handler 会长期休眠，缺省线程池是非守护线程，会阻塞 JVM 退出。
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "gate-engine-stub");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** SSE 帧序列写出：每帧一条 data 行 + 空行分隔（引擎据此切分事件）。 */
    private static void sse(HttpExchange ex, String... frames) throws IOException {
        ex.sendResponseHeaders(200, 0);   // 0 = chunked
        OutputStream out = ex.getResponseBody();
        for (String f : frames) {
            out.write(("data: " + f + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** 一条携载正文增量文本的标准 SSE 帧：content 值按 JSON 规则转义并加引号。 */
    private static String delta(String piece) {
        StringBuilder esc = new StringBuilder();
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c == '"' || c == '\\') {
                esc.append('\\').append(c);
            } else if (c < 0x20) {
                esc.append(String.format("\\u%04x", (int) c));
            } else {
                esc.append(c);
            }
        }
        return "{\"choices\":[{\"delta\":{\"content\":\"" + esc + "\"}}]}";
    }

    private static String usageOnly(int prompt, int completion, int total) {
        return "{\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion + ",\"total_tokens\":" + total + "}}";
    }

    private static final String FINDINGS_JSON =
            "{\"findings\":[{\"id\":\"f1\",\"severity\":\"high\",\"title\":\"SQL injection\","
                    + "\"message\":\"string concat\",\"suggestion\":\"use prepared statements\","
                    + "\"locations\":[{\"path\":\"src/A.java\",\"lines\":{\"start\":10,\"end\":12}}]}]}";

    private static String findingsJson() {
        return FINDINGS_JSON;
    }

    private static final String A40 = "a".repeat(40);
    private static final String B40 = "b".repeat(40);
    private static final String C40 = "c".repeat(40);
    private static final String D40 = "d".repeat(40);

    private static ReviewEngine.ReviewRequest req() {
        Snapshot snap = new Snapshot(ObjectId.of(A40), ObjectId.of(B40), ObjectId.of(C40),
                "refs/heads/main", List.of("src/A.java"), "diff --git a/src/A.java b/src/A.java",
                gate.domain.snapshot.CaptureIntegrityReport.clean());
        return new ReviewEngine.ReviewRequest(null, "T-9000", 3, snap, ObjectId.of(D40));
    }

    private static final class MemBlobStore implements BlobStore {
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
}
