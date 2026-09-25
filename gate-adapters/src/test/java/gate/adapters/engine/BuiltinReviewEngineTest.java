package gate.adapters.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;
import gate.domain.blob.BlobRef;
import gate.domain.policy.Decision;
import gate.domain.policy.GatePolicy;
import gate.domain.policy.Policy;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.review.SkippedPath;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    /** 脚本化响应列表：第 N 个请求取第 N 个，游标越界后重复最后一个（并发分组请求下无竞态）。 */
    private final List<HttpHandler> scriptList = java.util.Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger scriptCursor = new java.util.concurrent.atomic.AtomicInteger();
    /** 捕获的请求体（按到达顺序）：多轮/过滤断言用。 */
    private final List<String> requestBodies = java.util.Collections.synchronizedList(new ArrayList<>());

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

        ReviewEvidence evidence = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null,
                false, null, null, null).review(req());
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
        ReviewEvidence evidence = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null,
                false, null, null, null).review(req());
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
    void unparseableFinalAnswerIsRetriedOnceViaGraceRoundThenRecovers() throws Exception {
        String[] firstAttempt = resp(garbage());                    // 第 1 次：坏输出
        String[] grace = resp(delta(findingsJson()), "[DONE]");     // 宽限轮：交出正身
        startScripted(firstAttempt, grace);
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req()));
        assertEquals(1, report.findings().size(), "宽限轮抢救回发现，不再立刻判死");
        assertTrue(blobs.store.keySet().stream().anyMatch(p -> p.endsWith("builtin.grace.json")),
                "宽限轮原始输出独立留痕");
    }

    @Test
    void twoConsecutiveUnparseableRoundsFailClosed() throws Exception {
        startScripted(resp(garbage()), resp(garbage()));
        EngineFailure failure = assertInstanceOf(EngineFailure.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req()));
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, failure.kind());
        assertTrue(failure.detail().contains("grace"), failure.detail());
    }

    private static String garbage() {
        return delta("<<not valid json output>>");
    }

    @Test
    void sessionLogRecordsEveryCallAndTerminal() throws Exception {
        startScripted(
                resp(delta(findingsJson()), "[DONE]"),
                resp(delta("{\"analysis\":[],\"remove_ids\":[]}"), "[DONE]"));   // 过滤器：全放行
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, true, null, null, null).review(req()));

        assertTrue(blobs.store.containsKey("sessions/T-9000/3.jsonl"), "会话日志落盘 sessions/{ticket}/{round}.jsonl");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : new String(blobs.store.get("sessions/T-9000/3.jsonl"), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(PrismJson.parseObjectMap(line));
            }
        }
        assertEquals("header", lines.get(0).get("kind"));
        assertEquals("llm_call", lines.get(1).get("kind"));
        assertEquals("main", lines.get(1).get("phase"));
        assertTrue(String.valueOf(lines.get(1).get("user_prompt")).contains("unified diff"), "prompt 原文入账");
        assertTrue(String.valueOf(lines.get(1).get("response")).contains("findings"), "响应原文入账");
        assertEquals("filter", lines.get(2).get("phase"), "过滤调用独立入账");
        assertEquals("group_result", lines.get(3).get("kind"));
        assertEquals("completed", lines.get(3).get("status"));
        assertEquals("terminal", lines.get(4).get("kind"));
        assertEquals("report", lines.get(4).get("outcome"));
        assertEquals(1, report.findings().size());
    }

    @Test
    void resumeReusesCompletedGroupsFromFailedAttempt() throws Exception {
        String diff = cat(DIFF_ANCHOR, smallSection("app/D.java", "+DDD;"));
        List<String> changed = List.of("src/A.java", "app/D.java");
        startScripted(
                resp(delta(findingsFor("src/A.java", "ga")), "[DONE]"),   // 尝试1 组0：成功
                resp(garbage()),                                          // 尝试1 组1：坏输出
                resp(garbage()),                                          // 尝试1 组1 宽限：仍坏 → 整轮失败
                resp(delta(findingsFor("app/D.java", "gd2")), "[DONE]")); // 尝试2 组1：成功
        var eng = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, 1, null);

        EngineFailure attempt1 = assertInstanceOf(EngineFailure.class, eng.review(req(diff, changed, null)));
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, attempt1.kind());

        EngineReport attempt2 = assertInstanceOf(EngineReport.class, eng.review(req(diff, changed, null)));
        assertEquals(2, attempt2.findings().size(), "组0 复用 + 组1 重审 = 全覆盖");
        assertTrue(attempt2.findings().stream().anyMatch(f -> "ga".equals(f.ruleId())),
                "组0 的发现来自上次成功尝试（复用）");
        assertTrue(attempt2.findings().stream().anyMatch(f -> "gd2".equals(f.ruleId())),
                "组1 的发现是本次新审出的");
        String log = new String(blobs.store.get("sessions/T-9000/3.jsonl"), StandardCharsets.UTF_8);
        assertTrue(log.contains("\"kind\":\"group_reused\""), "续审在日志中留痕");
        assertTrue(log.contains("\"outcome\":\"report\""), "本次尝试以成功收尾");
    }

    @Test
    void noReuseAfterSuccessfulAttempt() throws Exception {
        String diff = cat(DIFF_ANCHOR, smallSection("app/D.java", "+DDD;"));
        List<String> changed = List.of("src/A.java", "app/D.java");
        startScripted(
                resp(delta(findingsFor("src/A.java", "ga")), "[DONE]"),
                resp(delta(findingsFor("app/D.java", "gd")), "[DONE]"),   // 尝试1 完整成功
                resp(garbage()));                                         // 尝试2：坏输出（重复）
        var eng = engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, 1, null);

        assertInstanceOf(EngineReport.class, eng.review(req(diff, changed, null)));
        // 尝试1 成功 → 尝试2 不允许复用（显式重审 = 全新审查）→ 坏输出照常失败
        EngineFailure attempt2 = assertInstanceOf(EngineFailure.class, eng.review(req(diff, changed, null)));
        assertEquals(EngineFailure.FailureKind.UNPARSEABLE, attempt2.kind());
    }

    private static String findingsFor(String path, String id) {
        return "{\"findings\":[{\"id\":\"" + id + "\",\"severity\":\"low\",\"title\":\"t\",\"message\":\"m\","
                + "\"locations\":[{\"path\":\"" + path + "\",\"lines\":{\"start\":1,\"end\":1}}]}]}";
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

    // ────────────────────────────── 新编排：回锚 / 过滤 / 闸门 / 分组 / 多轮 ──────────────────────────────

    @Test
    void existingCodeAnchorsFindingToNewSideLine() throws Exception {
        startScripted(resp(delta(findingsWithExcerpt("f1", "PreparedStatement ps = conn.prepareStatement(sql);")), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req(DIFF_ANCHOR)));
        Finding f = report.findings().get(0);
        assertEquals(12, f.lineStart(), "新增侧锚定：@@ -10,4 +11,5 @@ 中 added 行的新文件行号");
        assertEquals(12, f.lineEnd());
        assertEquals("PreparedStatement ps = conn.prepareStatement(sql);", f.existingCode());
    }

    @Test
    void existingCodeAnchorsToOldSideWhenOnlyDeletedMatches() throws Exception {
        startScripted(resp(delta(findingsWithExcerpt("f1", "Statement st = conn.createStatement();")), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req(DIFF_ANCHOR)));
        assertEquals(11, report.findings().get(0).lineStart(), "删除侧锚定：deleted 行的旧文件行号");
    }

    @Test
    void unanchorableExcerptKeepsModelLines() throws Exception {
        startScripted(resp(delta(findingsWithExcerpt("f1", "no such line anywhere in this diff;")), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req(DIFF_ANCHOR)));
        assertEquals(99, report.findings().get(0).lineStart(), "锚不上就保留模型行号，不猜");
    }

    @Test
    void filterPassRemovesProvablyWrongFindingAndKeepsEvidence() throws Exception {
        startScripted(
                resp(delta(twoFindingsJson()), usageOnly(100, 10, 110), "[DONE]"),
                resp(delta("{\"analysis\":[{\"id\":\"f1\",\"verdict\":\"keep\",\"reason\":\"代码在 diff 中\"},"
                        + "{\"id\":\"f2\",\"verdict\":\"remove\",\"reason\":\"指认代码不在 diff 中\"}],"
                        + "\"remove_ids\":[\"f2\"]}"), usageOnly(200, 20, 220), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, true, null, null, null).review(req(DIFF_ANCHOR)));
        assertEquals(1, report.findings().size(), "f2 被'宁留勿删'过滤器删除");
        assertEquals("f1", report.findings().get(0).ruleId());
        assertEquals(1, report.filteredFindings().size(), "被过滤的发现留痕，不是静默丢弃");
        assertEquals("f2", report.filteredFindings().get(0).ruleId());
        assertEquals(300L, report.promptTokens(), "过滤调用的 usage 并入总账");
        assertEquals(330L, report.totalTokens());
    }

    @Test
    void filterFailureIsFailOpenNotBlocking() throws Exception {
        startScripted(
                resp(delta(findingsJson()), "[DONE]"),
                resp(delta("garbage, not json"), "[DONE]"));   // 过滤输出坏 → 全部保留
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, true, null, null, null).review(req()));
        assertEquals(1, report.findings().size(), "过滤器失败绝不丢发现");
        assertEquals(0, report.filteredFindings().size());
    }

    @Test
    void oversizedFileIsSkippedByTokenGateAndPolicyRoutesToHuman() throws Exception {
        String diff = cat(DIFF_ANCHOR, smallSection("big/Big.java", "+x".repeat(8000)));
        List<String> changed = List.of("src/A.java", "big/Big.java");
        startScripted(resp(delta("{\"findings\":[]}"), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, 1500L)
                        .review(req(diff, changed, null)));
        assertTrue(report.skippedPaths().contains(new SkippedPath("big/Big.java", SkippedPath.TOO_LARGE)),
                "超限文件确定性跳审并留痕");
        assertFalse(report.coveredPaths().contains("big/Big.java"));
        assertTrue(report.coveredPaths().contains("src/A.java"));

        Decision d = new GatePolicy().decide("T-9000", 3, report, snapOf(changed, diff), Policy.defaults());
        assertEquals(Decision.Verdict.REQUIRES_HUMAN, d.verdict(), "too_large 是真实代码未审 → 转人工");
        assertTrue(d.detail().contains("big/Big.java"));
    }

    @Test
    void binarySecretDeletedPathsAreSkippedSilentlyAndPolicyPasses() throws Exception {
        String diff = cat(deletedSection("src/Old.java"), binarySection("img/logo.png"),
                smallSection(".env", "+SECRET_KEY=abcd"), DIFF_ANCHOR);
        List<String> changed = List.of("src/Old.java", "img/logo.png", ".env", "src/A.java");
        startScripted(resp(delta("{\"findings\":[]}"), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null)
                        .review(req(diff, changed, null)));
        assertTrue(report.skippedPaths().contains(new SkippedPath("src/Old.java", SkippedPath.DELETED)));
        assertTrue(report.skippedPaths().contains(new SkippedPath("img/logo.png", SkippedPath.BINARY)));
        assertTrue(report.skippedPaths().contains(new SkippedPath(".env", SkippedPath.SECRET_PATH)));
        assertEquals(Set.of("src/A.java"), report.coveredPaths());

        Decision d = new GatePolicy().decide("T-9000", 3, report, snapOf(changed, diff), Policy.defaults());
        assertEquals(Decision.Verdict.PASS, d.verdict(), "介质性跳过不阻断，覆盖分母按授权扣除");
    }

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void rulesJsonInjectsRuleTextAndHonorsSkip() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve(".gate"));
        java.nio.file.Files.writeString(tempDir.resolve(".gate").resolve("rules.json"),
                "{\"rules\":[{\"glob\":\"**/*.sql\",\"rule\":\"检查 SQL 注入与迁移可回滚\"},"
                        + "{\"glob\":\"src/generated/**\",\"skip\":true}]}",
                java.nio.charset.StandardCharsets.UTF_8);
        String diff = cat(smallSection("migrations/V1.sql", "+CREATE TABLE t(id int);"),
                smallSection("src/generated/Gen.java", "+AUTO GENERATED CODE"), DIFF_ANCHOR);
        startScripted(resp(delta("{\"findings\":[]}"), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null)
                        .review(req(diff, List.of("migrations/V1.sql", "src/generated/Gen.java", "src/A.java"), tempDir)));
        assertTrue(requestBodies.stream().anyMatch(b -> b.contains("检查 SQL 注入与迁移可回滚")),
                "规则文本进入 prompt（分组并发下不依赖请求顺序）");
        assertTrue(report.skippedPaths().contains(new SkippedPath("src/generated/Gen.java", SkippedPath.RULE_SKIP)),
                "项目规则显式跳过");
        assertTrue(report.coveredPaths().containsAll(List.of("migrations/V1.sql", "src/A.java")));
    }

    @Test
    void multiRoundInjectsConfirmedFindingsAndStopsOnEmptyRound() throws Exception {
        startScripted(
                resp(delta(findingsJson()), "[DONE]"),
                resp(delta("{\"findings\":[]}"), "[DONE]"));
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, 2, null, null).review(req()));
        assertEquals(1, report.findings().size());
        assertEquals(2, requestBodies.size(), "两轮各一次调用，空轮即停");
        assertTrue(requestBodies.get(1).contains("已确认的发现"), "第 2 轮回注已确认发现");
        assertTrue(requestBodies.get(1).contains("SQL injection"));
    }

    @Test
    void multiDirChangelistFormsIndependentGroupsAndAggregatesFindings() throws Exception {
        String diff = cat(smallSection("src/A.java", "+AAA;"), smallSection("docs/C.md", "+BBB;"),
                smallSection("app/D.java", "+CCC;"));
        start(ex -> {
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = body.contains("+AAA;") ? "src/A.java"
                        : body.contains("+BBB;") ? "docs/C.md" : "app/D.java";
                sse(ex, delta("{\"findings\":[{\"id\":\"g1\",\"severity\":\"low\",\"title\":\"t\",\"message\":\"m\","
                        + "\"locations\":[{\"path\":\"" + path + "\",\"lines\":{\"start\":1,\"end\":1}}]}]}"), "[DONE]");
                ex.close();
            } catch (IOException ignored) {
            }
        });
        EngineReport report = assertInstanceOf(EngineReport.class,
                engine(Duration.ofSeconds(10), Duration.ofSeconds(5), null, false, null, null, null).review(req(diff)));
        assertEquals(3, report.findings().size(), "三个顶层目录 = 三组独立审查，发现全量聚合");
        assertEquals(Set.of("src/A.java", "docs/C.md", "app/D.java"), report.coveredPaths());
    }

    // ────────────────────────────── 夹具 ──────────────────────────────

    /** 带锚定语义的 diff：@@ -10,4 +11,5 @@ —— added 行新号 12，deleted 行旧号 11。 */
    private static final String DIFF_ANCHOR = String.join("\n",
            "diff --git a/src/A.java b/src/A.java",
            "index 1111111..2222222 100644",
            "--- a/src/A.java",
            "+++ b/src/A.java",
            "@@ -10,4 +11,5 @@ class A {",
            "     void q(String name) throws Exception {",
            "-        Statement st = conn.createStatement();",
            "+        PreparedStatement ps = conn.prepareStatement(sql);",
            "         ps.execute();",
            "     }");

    /** 多个 section 以换行衔接——diff --git 头必须顶行开头，粘连会让切片失败。 */
    private static String cat(String... sections) {
        return String.join("\n", sections);
    }

    private static String smallSection(String path, String addedLine) {
        return String.join("\n",
                "diff --git a/" + path + " b/" + path,
                "index 1111111..2222222 100644",
                "--- a/" + path,
                "+++ b/" + path,
                "@@ -1,1 +1,2 @@",
                " existing",
                addedLine);
    }

    private static String deletedSection(String path) {
        return String.join("\n",
                "diff --git a/" + path + " b/" + path,
                "deleted file mode 100644",
                "index 1111111..0000000",
                "--- a/" + path,
                "+++ /dev/null",
                "@@ -1,1 +0,0 @@",
                "-old content");
    }

    private static String binarySection(String path) {
        return String.join("\n",
                "diff --git a/" + path + " b/" + path,
                "index 1111111..2222222 100644",
                "Binary files a/" + path + " and b/" + path + " differ");
    }

    private static String findingsWithExcerpt(String id, String existingCode) {
        String esc = existingCode.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"findings\":[{\"id\":\"" + id + "\",\"severity\":\"high\",\"title\":\"t\",\"message\":\"m\","
                + "\"existing_code\":\"" + esc + "\","
                + "\"locations\":[{\"path\":\"src/A.java\",\"lines\":{\"start\":99,\"end\":99}}]}]}";
    }

    private static String twoFindingsJson() {
        return "{\"findings\":["
                + findingWithExcerpt("f1", "PreparedStatement ps = conn.prepareStatement(sql);", 99)
                + ","
                + findingWithExcerpt("f2", "GHOST_CODE_THAT_IS_NOT_IN_DIFF = true;", 42)
                + "]}";
    }

    private static String findingWithExcerpt(String id, String excerpt, int line) {
        String esc = excerpt.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"" + id + "\",\"severity\":\"medium\",\"title\":\"t\",\"message\":\"m\","
                + "\"existing_code\":\"" + esc + "\","
                + "\"locations\":[{\"path\":\"src/A.java\",\"lines\":{\"start\":" + line + ",\"end\":" + line + "}}]}";
    }

    private static ReviewEngine.ReviewRequest req(String diff) {
        return req(diff, List.of("src/A.java"), null);
    }

    private static ReviewEngine.ReviewRequest req(String diff, List<String> changed, java.nio.file.Path cloneRepo) {
        Snapshot snap = new Snapshot(ObjectId.of(A40), ObjectId.of(B40), ObjectId.of(C40),
                "refs/heads/main", changed, diff, gate.domain.snapshot.CaptureIntegrityReport.clean());
        return new ReviewEngine.ReviewRequest(
                cloneRepo == null ? null : gate.domain.git.RepoRef.of(cloneRepo),
                "T-9000", 3, snap, ObjectId.of(D40));
    }

    private static Snapshot snapOf(List<String> changed, String diff) {
        return new Snapshot(ObjectId.of(A40), ObjectId.of(B40), ObjectId.of(C40),
                "refs/heads/main", changed, diff,
                gate.domain.snapshot.CaptureIntegrityReport.clean());
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
        return engine(total, idle, maxTokens, null, null, null, null);
    }

    private BuiltinReviewEngine engine(Duration total, Duration idle, Long maxTokens,
                                       Boolean filter, Integer rounds, Integer concurrency, Long maxFileTokens) {
        if (blobs == null) {
            blobs = new MemBlobStore();   // 同一用例内多次构造引擎共享 store：extractCost 不读 store
        }
        return new BuiltinReviewEngine(blobs, total, idle, "temp", "test-model",
                "http://127.0.0.1:" + port() + "/v1", "sk-test", maxTokens,
                filter, rounds, concurrency, maxFileTokens, null);
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

    /**
     * 脚本化多响应 stub：第 N 个 HTTP 请求拿到第 N 个响应；用尽后重复最后一个——
     * 过滤/宽限轮拿同一响应时天然走 fail-open/复现路径，单响应用例无需感知。
     * 游标取号保证并发分组请求下无竞态（用尽后始终有 handler 应答）。
     */
    private void startScripted(String[]... responses) throws IOException {
        scriptList.clear();
        for (String[] r : responses) {
            scriptList.add(ex -> {
                try {
                    sse(ex, r);
                    ex.close();
                } catch (IOException ignored) {
                }
            });
        }
        scriptCursor.set(0);
        start(ex -> {
            try {
                requestBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
            int i = scriptCursor.getAndIncrement();
            int top = scriptList.size() - 1;
            scriptList.get(Math.max(0, Math.min(i, top))).handle(ex);
        });
    }

    /** 一条响应 = 一组 SSE 帧。 */
    private static String[] resp(String... frames) {
        return frames;
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
