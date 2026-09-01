package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.ports.session.CostHint;
import gate.ports.store.BlobStore;
import gate.ports.engine.ReviewEngine;
import gate.adapters.engine.PrismJson.PrismFinding;
import gate.adapters.engine.PrismJson.PrismLocation;
import gate.adapters.engine.PrismJson.PrismOutput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gate 内建审查引擎（{@code engine.kind = "gate-engine"}，当前唯一引擎）：进程内直连 OpenAI 兼容
 * {@code /chat/completions}，以 SSE 流式消费。取代已删除的外部 prism 二进制路径——prism 的教训：
 * 非流式整包等待 + 内部不可控重试 + 超时不可配，慢速 thinking 模型必然被网关边缘掐断。
 *
 * <p><b>看门狗是本类的存在理由。</b>{@code HttpRequest.timeout} 只覆盖到响应头到达，管不住 body
 * 阶段；读循环里的空闲判断也只有下一帧到来时才执行得到。因此由专用守护线程持有两个定时：
 * 总超时（{@code timeout_seconds}）与空闲超时（{@code idle_timeout_seconds}，每个事件到达即重排），
 * 任一到期即关闭响应流，使阻塞中的 {@code readLine()} 立即抛出并归类为 TIMEOUT。
 * 「黑洞测试」（stub 服务连上后一字节不发）是这条链路存在的唯一可信证明，见 BuiltinReviewEngineTest。
 *
 * <p><b>Contract honoured: {@link #review} never throws.</b> 连接失败 / HTTP 非 2xx / 200 内联
 * error 帧 / 读流中断 / 超时 / 非法 JSON 全部落为 {@link EngineFailure} 值交策略拒绝——fail-closed
 * 是结构性质，不是习惯。
 *
 * <p><b>SSE 细则</b>：仅 {@code data:} 帧参与内容；注释心跳行不重置空闲窗口（keepalive 不能洗白
 * 真停流）；同一事件的多条 data 行按规范以 \n 连接后解析；{@code [DONE]} 正常收尾，EOF 无哨兵也
 * 允许收尾；usage 帧 choices 为空数组需判空；无法解析的单帧按噪声跳过不终止。
 *
 * <p><b>遥测</b>：尾帧 usage 作为 token 字段直接随 {@link EngineReport} 闭环传递，extractCost 只读
 * 报告字段——引擎实例零状态，异步化也不会串轮错数。上游无 usage 时 tokenSource=unavailable；
 * 遥测任何异常都不阻塞发布（P4 bypass）。API key 只进 Authorization 头，绝不进 argv / 日志。
 */
public final class BuiltinReviewEngine implements ReviewEngine {

    public static final String ENGINE_ID = "gate-engine";
    /** 与 {@code gate.domain.config.GateConfig.EngineConfig#KIND_GATE_ENGINE} 同值。 */
    public static final String KIND = "gate-engine";

    private static final String SYSTEM_PROMPT = """
            你是一个代码审查引擎。审查给定提交（unified diff），只输出一个 JSON 对象，不要 markdown 包裹、不要前后缀解释：
            {"findings":[{"id":"fx","severity":"low|medium|high","title":"...","message":"...","suggestion":"...","locations":[{"path":"...","lines":{"start":1,"end":1}}]}]}
            severity 只允许 low/medium/high；没有发现则输出 {"findings":[]}。只报告真实的 bug/风险，不凑数。
            """;

    /** 进程级共享 HttpClient：连接建立上限 15s；HttpClient 不可变且线程安全。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 看门狗共享调度池：单守护线程足够——每个回合只有两个轻量定时任务。 */
    private static final ScheduledExecutorService WATCHDOG_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gate-engine-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final BlobStore blobStore;
    private final Duration totalTimeout;
    private final Duration idleTimeout;
    private final String providerId;
    private final String modelName;
    private final String baseUrl;
    private final String apiKey;   // 来自 provider 行（KMS 解密瞬间），只进 Authorization 头
    private final Long maxTokens;  // 可选输出上限；null 则不注入请求体

    public BuiltinReviewEngine(BlobStore blobStore, Duration totalTimeout, Duration idleTimeout,
                               String providerId, String modelName, String baseUrl, String apiKey,
                               Long maxTokens) {
        this.blobStore = blobStore;
        this.totalTimeout = totalTimeout;
        this.idleTimeout = idleTimeout;
        this.providerId = providerId;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(ENGINE_ID, "v1", "builtin:chat.completions", providerId, modelName);
    }

    @Override
    public ReviewEvidence review(ReviewRequest request) {
        EngineDescriptor descriptor = describe();
        // 取消早退：任务被取消后再发起新的 LLM 调用纯属烧钱。
        if (Thread.currentThread().isInterrupted()) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "aborted by task cancellation (before connect)", -1);
        }
        try {
            return runStreaming(request, descriptor);
        } catch (Throwable t) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "gate-engine failed: " + t, -1);
        }
    }

    private ReviewEvidence runStreaming(ReviewRequest request, EngineDescriptor descriptor) {
        Instant started = Instant.now();
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(jsonEscape(modelName)).append("\",\"stream\":true")
                .append(",\"stream_options\":{\"include_usage\":true}");
        if (maxTokens != null) {
            body.append(",\"max_tokens\":").append(maxTokens);
        }
        body.append(",\"messages\":[{\"role\":\"system\",\"content\":").append(jsonLiteral(SYSTEM_PROMPT))
                .append("},{\"role\":\"user\",\"content\":").append(jsonLiteral(buildUserPrompt(request)))
                .append("}]}");

        HttpResponse<InputStream> upstream;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(totalTimeout)   // 覆盖到响应头为止；body 阶段由看门狗兜底
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            upstream = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream connect failed: " + e.getMessage(), -1);
        }
        if (upstream.statusCode() / 100 != 2) {
            String snippet;
            try (InputStream in = upstream.body()) {
                snippet = new String(in.readNBytes(1024), StandardCharsets.UTF_8).replace('\n', ' ');
            } catch (Exception e) {
                snippet = "<unable to read error body>";
            }
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "upstream HTTP " + upstream.statusCode() + ": " + snippet.trim(), -1);
        }

        return consumeSse(request, descriptor, upstream, started);
    }

    /** 消费 SSE 流：读循环跑在专用 reader 线程上，调用线程按自适应期限盯 Future。 */
    private ReviewEvidence consumeSse(ReviewRequest request, EngineDescriptor descriptor,
                                      HttpResponse<InputStream> upstream, Instant started) {
        // 看门狗到期时置位触发窗口名；reader 据此把 IOException 分类为 TIMEOUT。
        AtomicReference<String> firedWindow = new AtomicReference<>(null);
        // reader 每收到一个数据事件就刷新该时刻；调用方据此推算「idle 截止」，即使底层
        // close() 唤不醒阻塞中的 readLine（个别 JDK HttpClient 栈行为），也能按时判决。
        java.util.concurrent.atomic.AtomicLong lastDataAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        InputStream stream = upstream.body();

        java.util.concurrent.FutureTask<ReviewEvidence> reader = new java.util.concurrent.FutureTask<>(() ->
                readSseLoop(request, descriptor, stream, started, firedWindow, lastDataAt));
        Thread readerThread = new Thread(reader, "gate-engine-reader-" + request.ticketNo());
        readerThread.setDaemon(true);
        readerThread.start();

        long startMs = System.currentTimeMillis();
        long totalDeadline = startMs + totalTimeout.toMillis() + 2_000L;   // 缓冲：给正常 reader 收尾留余量
        long idleGrace = idleTimeout.toMillis() + 2_000L;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                long deadline = Math.min(totalDeadline, lastDataAt.get() + idleGrace);
                if (now >= deadline) {
                    String window = firedWindow.get();
                    return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                            (window == null ? (now >= totalDeadline ? "total" : "idle") : window)
                                    + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                    + "s (deadline enforced by caller)",
                            -1);
                }
                try {
                    return reader.get(deadline - now, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException notYet) {
                    // 期限已到但 reader 可能刚好在最后时刻推进了 lastDataAt——重算后最多再等一轮。
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream reader failed: " + cause.getMessage(), -1);
        } catch (InterruptedException e) {
            // 任务取消（TaskRunner 中断调用线程）：不再烧上游 token，立即返回。
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "aborted by task cancellation", -1);
        } finally {
            // 无论结果如何都释放：中断 reader（取消即中断）并关流。
            reader.cancel(true);
            closeQuietly(stream);
        }
    }

    /** reader 线程的读循环主体；返回证据或失败值，永不抛出。 */
    private ReviewEvidence readSseLoop(ReviewRequest request, EngineDescriptor descriptor,
                                       InputStream stream, Instant started,
                                       AtomicReference<String> firedWindow,
                                       java.util.concurrent.atomic.AtomicLong lastDataAt) {
        StringBuilder content = new StringBuilder();
        Long promptTokens = null, completionTokens = null, totalTokens = null;

        final ScheduledFuture<?>[] idleHandle = new ScheduledFuture<?>[1];
        ScheduledFuture<?> totalHandle = WATCHDOG_POOL.schedule(() -> {
            if (firedWindow.compareAndSet(null, "total")) {
                closeQuietly(stream);
            }
        }, totalTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        // 空闲定时重排：仅由 reader 线程调用；注释心跳行不得触发（keepalive 不能洗白真停流）。
        Runnable armIdle = () -> {
            if (idleHandle[0] != null) {
                idleHandle[0].cancel(false);
            }
            idleHandle[0] = WATCHDOG_POOL.schedule(() -> {
                if (firedWindow.compareAndSet(null, "idle")) {
                    closeQuietly(stream);
                }
            }, idleTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        };

        boolean doneSentinel = false;
        StringBuilder eventData = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            armIdle.run();
            readLoop:
            while (true) {
                String line;
                try {
                    line = br.readLine();
                } catch (IOException e) {
                    String window = firedWindow.get();
                    if (window != null) {
                        return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                                window + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                        + "s (" + content.length() + " chars in)", -1);
                    }
                    throw e;
                }
                if (line == null) {
                    break;   // EOF：部分网关不发 [DONE] 哨兵，允许正常收尾
                }
                if (Thread.currentThread().isInterrupted()) {
                    return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                            "aborted by task cancellation (" + content.length() + " chars in)", -1);
                }
                if (line.isBlank()) {
                    if (eventData.length() == 0) {
                        continue;
                    }
                    String payload = eventData.toString();
                    eventData.setLength(0);
                    String trimmed = payload.trim();
                    if ("[DONE]".equals(trimmed)) {
                        doneSentinel = true;
                        break readLoop;
                    }
                    // —— 解析一帧 ——
                    Map<String, Object> chunk;
                    try {
                        chunk = PrismJson.parseObjectMap(payload);
                    } catch (RuntimeException e) {
                        continue;   // 无法解析的单帧按噪声跳过（网关心跳/非标帧）
                    }
                    if (chunk.get("error") instanceof Map<?, ?> err) {
                        Object msg = err.get("message");
                        return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                                "upstream inline error: " + truncate(msg == null ? String.valueOf(err) : String.valueOf(msg), 1024),
                                -1);
                    }
                    if (chunk.get("usage") instanceof Map<?, ?> u) {
                        promptTokens = longOr(promptTokens, u.get("prompt_tokens"));
                        completionTokens = longOr(completionTokens, u.get("completion_tokens"));
                        totalTokens = longOr(totalTokens, u.get("total_tokens"));
                    }
                    if (chunk.get("choices") instanceof List<?> list && !list.isEmpty()
                            && list.get(0) instanceof Map<?, ?> c0
                            && c0.get("delta") instanceof Map<?, ?> delta
                            && delta.get("content") instanceof String piece) {
                        content.append(piece);
                    }
                    armIdle.run();   // 只有真正的数据事件才重排空闲窗口
                    lastDataAt.set(System.currentTimeMillis());   // 调用方据此推算 idle 截止
                } else if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');   // SSE 规范：同事件多条 data 行以 \n 连接
                    }
                    eventData.append(line.substring(5).stripLeading());
                }
                // 其余行（event:/id:/retry: 等）忽略
            }
            if (!doneSentinel && eventData.length() > 0) {
                // EOF 冲刷残余事件：部分网关 usage 帧后不换行就断流。
                try {
                    Map<String, Object> chunk = PrismJson.parseObjectMap(eventData.toString().trim());
                    if (chunk.get("usage") instanceof Map<?, ?> u) {
                        promptTokens = longOr(promptTokens, u.get("prompt_tokens"));
                        completionTokens = longOr(completionTokens, u.get("completion_tokens"));
                        totalTokens = longOr(totalTokens, u.get("total_tokens"));
                    }
                } catch (RuntimeException ignored) {
                    // 残余坏帧不值得失败——主体内容已在 content 里
                }
            }
        } catch (Exception e) {
            String window = firedWindow.get();
            if (window != null) {
                return new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                        window + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                + "s (" + content.length() + " chars in)", -1);
            }
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream crashed: " + e.getMessage(), -1);
        } finally {
            totalHandle.cancel(false);
            if (idleHandle[0] != null) {
                idleHandle[0].cancel(false);
            }
            closeQuietly(stream);
        }

        return finishReport(request, descriptor, content.toString(), started,
                promptTokens, completionTokens, totalTokens);
    }

    /** 原样存 blob → 围栏/think 块剥离仅用于解析 → 组装报告（usage 随证据闭环传递）。
     *  blob 存的是上游原封不动的完整输出（含可能的 ```json 围栏与 &lt;think&gt; 思维链），
     *  UNPARSEABLE 失败时它是排查指令遵循/格式错误的唯一现场。 */
    private ReviewEvidence finishReport(ReviewRequest request, EngineDescriptor descriptor,
                                        String rawContent, Instant started,
                                        Long promptTokens, Long completionTokens, Long totalTokens) {
        Duration wall = Duration.between(started, Instant.now());
        // 原样保存（不做任何清洗/截断），后续无论成功或失败都可核对原始应答。
        BlobRef rawRef = blobStore.put(rawContent.getBytes(StandardCharsets.UTF_8),
                "raw/" + request.ticketNo() + "/" + request.reviewRound() + "/builtin.json");

        String raw = stripForParse(rawContent);
        PrismOutput out;
        try {
            out = PrismJson.parse(raw);
        } catch (RuntimeException e) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.UNPARSEABLE,
                    "engine JSON unparseable: " + e.getMessage()
                            + "; raw persisted at " + rawRef.relPath() + " (bytes=" + rawContent.length() + ")"
                            + "; first 200 chars=" + truncate(rawContent, 200), -1);
        }

        Set<String> covered = new LinkedHashSet<>(request.snapshot().changedPaths());
        List<Finding> findings = new ArrayList<>();
        boolean unknownSeverity = false;
        for (PrismFinding pf : out.findings) {
            Severity sev = mapSeverity(pf.severity);
            if (sev == Severity.BLOCKER && !"high".equalsIgnoreCase(pf.severity)) {
                unknownSeverity = true;   // 与原适配器同款沉降规则：未知词 → BLOCKER + degraded
            }
            String path = firstLocationPath(pf);
            int[] lines = firstLocationLines(pf);
            Integer lineStart = lines == null ? null : lines[0];
            Integer lineEnd = lines == null ? null : lines[1];
            String message = pf.title == null || pf.title.isBlank()
                    ? (pf.message == null ? "" : pf.message)
                    : (pf.message == null || pf.message.isBlank() ? pf.title : pf.title + ": " + pf.message);
            findings.add(new Finding(sev, pf.severity == null ? "" : pf.severity,
                    path, lineStart, lineEnd, pf.id, message, pf.suggestion));
        }

        return new EngineReport(descriptor, request.snapshot().treeHash().hex(),
                findings, covered, unknownSeverity, rawRef, 0, wall,
                promptTokens, completionTokens, totalTokens);
    }

    /** 调用侧收尾在 finally 中完成；reader 线程的收尾在 readSseLoop 自己的 finally 中完成。 */

    /**
     * P4 cost telemetry：从报告携带的 usage 字段取值（tokenSource=stream_usage）；
     * 无 usage 时诚实降级 unavailable。Bypass 数据——任何异常都不能影响发布。
     */
    @Override
    public java.util.Optional<CostHint> extractCost(ReviewEvidence evidence) {
        return evidence.accept(new EvidenceVisitor<java.util.Optional<CostHint>>() {
            @Override
            public java.util.Optional<CostHint> visit(EngineReport report) {
                return java.util.Optional.of(new CostHint(
                        report.promptTokens(), report.completionTokens(), report.totalTokens(),
                        report.totalTokens() == null ? "unavailable" : "stream_usage",
                        report.duration().toMillis(), null));
            }

            @Override
            public java.util.Optional<CostHint> visit(EngineFailure failure) {
                return java.util.Optional.of(CostHint.EMPTY);
            }
        });
    }

    private static void closeQuietly(InputStream s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 关闭失败对看门狗语义无害：读侧最终也会因总时长或 EOF 退出
        }
    }

    private static String buildUserPrompt(ReviewRequest request) {
        return "工单 " + request.ticketNo() + " · 第 " + request.reviewRound() + " 轮\n"
                + "请审查下列变更并找出问题（unified diff）：\n\n" + request.snapshot().diff();
    }

    private static Long longOr(Long base, Object v) {
        if (v instanceof Number n) return n.longValue();
        return base;
    }

    private static String jsonLiteral(String s) {
        return jsonEscape(s, true);
    }

    private static String jsonEscape(String s) {
        return jsonEscape(s, false);
    }

    private static String jsonEscape(String s, boolean quote) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        if (quote) sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        if (quote) sb.append('"');
        return sb.toString();
    }

    /** 模型偶发会在 JSON 外再裹一层 ```json 围栏，容忍性剥掉。 */
    private static String stripCodeFence(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                int end = t.lastIndexOf("```");
                t = (end > nl) ? t.substring(nl + 1, end).trim() : t.substring(nl + 1).trim();
            }
        }
        return t;
    }

    /** thinking 模型（如 kimi-k3）把思维链以 &lt;think&gt;…&lt;/think&gt; 内联进 content，真身 JSON 只在
     *  闭合标记之后；未闭合意味着正文没有 JSON——剥成空串走 UNPARSEABLE（blob 留有完整原文）。 */
    private static String stripThinkBlock(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (!t.startsWith("<think>")) {
            return raw;
        }
        int end = t.indexOf("</think>");
        return end < 0 ? "" : t.substring(end + "</think>".length()).trim();
    }

    /** 解析输入的容忍性清洗：think 块与围栏都可能包住真身 JSON，且两种嵌套方向都会出现，
     *  故围栏剥两次、think 剥中间一次。只作用于解析，blob 落盘永远走原样。 */
    private static String stripForParse(String raw) {
        return stripCodeFence(stripThinkBlock(stripCodeFence(raw)));
    }

    private static Severity mapSeverity(String raw) {
        return switch (raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> Severity.INFO;
            case "medium" -> Severity.WARNING;
            case "high" -> Severity.BLOCKER;
            default -> Severity.BLOCKER;
        };
    }

    private static String firstLocationPath(PrismFinding pf) {
        if (pf.locations != null && !pf.locations.isEmpty() && pf.locations.get(0).path != null) {
            return pf.locations.get(0).path;
        }
        return ".";
    }

    private static int[] firstLocationLines(PrismFinding pf) {
        if (pf.locations == null || pf.locations.isEmpty()) return null;
        PrismLocation loc = pf.locations.get(0);
        if (loc.lines == null) return null;
        int start = loc.lines.start == null ? 0 : loc.lines.start;
        int end = loc.lines.end == null ? start : loc.lines.end;
        return new int[]{start, end};
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
