package gate.adapters.engine;

import gate.domain.blob.BlobRef;
import gate.domain.review.EngineDescriptor;
import gate.domain.review.EngineFailure;
import gate.domain.review.EngineReport;
import gate.domain.review.EvidenceVisitor;
import gate.domain.review.Finding;
import gate.domain.review.ReviewEvidence;
import gate.domain.review.Severity;
import gate.domain.review.SkippedPath;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gate 内建审查引擎（{@code engine.kind = "gate-engine"}，当前唯一引擎）：进程内直连 OpenAI 兼容
 * {@code /chat/completions}，以 SSE 流式消费。取代已删除的外部 prism 二进制路径——prism 的教训：
 * 非流式整包等待 + 内部不可控重试 + 超时不可配，慢速 thinking 模型必然被网关边缘掐断。
 *
 * <p><b>编排：确定性工程 × LLM 混合（反哺自 alibaba/open-code-review）。</b>"不允许出错"的步骤
 * 全部由代码完成，LLM 只做动态判断：
 * <ol>
 *   <li><b>审前闸门</b>（{@link DiffSections}）：按文件切片，二进制/密钥/删除/规则跳过/超限文件
 *       确定性排除并记入证据（{@link SkippedPath}），而不是烧 token 或打爆上下文窗口；</li>
 *   <li><b>分组切片</b>：按目录确定性分组、每组独立上下文并发审查——大变更不再被"一次性整包
 *       prompt"挤压，组内 token 预算超限自动再切；</li>
 *   <li><b>多轮累积</b>（可选）：第 2 轮起把已确认发现回注 prompt 并只找新问题，一轮无新发现
 *       即停——plan 不会成为覆盖度天花板，发现数有硬上限；</li>
 *   <li><b>宽限轮 + 失败连击</b>：输出无法解析时给一次"只输出 JSON"的最后机会，连续第二次解析
 *       失败才判 UNPARSEABLE——模型换措辞无限重发同一坏输出的循环被硬性截断；</li>
 *   <li><b>确定性回锚</b>（{@link FindingAnchor}）：发现必须带 {@code existing_code} 逐字摘录，
 *       行号由代码从 diff 滑窗匹配推导，不信 LLM 报的行号——位置漂移在源头被消灭；</li>
 *   <li><b>过滤 pass</b>（可选，宁留勿删）：独立的事实核查调用只删除"diff 能证明错误"的发现，
 *       证据不足一律放行；过滤器失败 fail-open 保持全部发现——它优化精度，绝不阻塞审查。</li>
 *   <li><b>会话逐请求留痕 + 续审</b>（{@link ReviewSessionLog}）：每次 LLM 调用的 prompt/响应/
 *       usage/耗时逐行落 {@code sessions/{ticket}/{round}.jsonl}——过程可回放；同一轮的上一次
 *       尝试失败时，按组指纹复用已完成组的发现，只重派失败组（成功尝试永不复用）。</li>
 *   <li><b>delegate 规格书</b>（{@link ReviewSpecBuilder}）：同一套确定性工程打包成 MCP 工具
 *       {@code gate_review_spec} 交给编码 Agent 自查——咨询性材料，绝不成为门禁证据。</li>
 * </ol>
 *
 * <p><b>看门狗是本类存在的前提。</b>{@code HttpRequest.timeout} 只覆盖到响应头到达，管不住 body
 * 阶段；读循环里的空闲判断也只有下一帧到来时才执行得到。因此由专用守护线程持有两个定时：
 * 总超时（{@code timeout_seconds}）与空闲超时（{@code idle_timeout_seconds}，每个事件到达即重排），
 * 任一到期即关闭响应流，使阻塞中的 {@code readLine()} 立即抛出并归类为 TIMEOUT。
 * 「黑洞测试」（stub 服务连上后一字节不发）是这条链路存在的唯一可信证明，见 BuiltinReviewEngineTest。
 *
 * <p><b>Contract honoured: {@link #review} never throws.</b> 连接失败 / HTTP 非 2xx / 200 内联
 * error 帧 / 读流中断 / 超时 / 非法 JSON 全部落为 {@link EngineFailure} 值交策略拒绝——fail-closed
 * 是结构性质，不是习惯。任何一组硬失败即整轮失败（部分覆盖的静默放行比失败更危险）；
 * 工单保持 PRESUBMITTED 可原地重试。
 *
 * <p><b>SSE 细则</b>：仅 {@code data:} 帧参与内容；注释心跳行不重置空闲窗口（keepalive 不能洗白
 * 真停流）；同一事件的多条 data 行按规范以 \n 连接后解析；{@code [DONE]} 正常收尾，EOF 无哨兵也
 * 允许收尾；usage 帧 choices 为空数组需判空；无法解析的单帧按噪声跳过不终止。
 *
 * <p><b>遥测</b>：各调用（审查/宽限/过滤）的 usage 求和后随 {@link EngineReport} 闭环传递，
 * extractCost 只读报告字段。上游无 usage 时 tokenSource=unavailable；遥测任何异常都不阻塞发布
 * （P4 bypass）。API key 只进 Authorization 头，绝不进 argv / 日志。
 */
public final class BuiltinReviewEngine implements ReviewEngine {

    public static final String ENGINE_ID = "gate-engine";
    /** 与 {@code gate.domain.config.GateConfig.EngineConfig#KIND_GATE_ENGINE} 同值。 */
    public static final String KIND = "gate-engine";

    private static final String SYSTEM_PROMPT = """
            你是一个代码审查引擎。审查给定提交（unified diff），只输出一个 JSON 对象，不要 markdown 包裹、不要前后缀解释：
            {"findings":[{"id":"fx","severity":"low|medium|high","title":"...","message":"...","suggestion":"...","existing_code":"...","locations":[{"path":"...","lines":{"start":1,"end":1}}]}]}
            severity 只允许 low/medium/high；没有发现则输出 {"findings":[]}。只报告真实的 bug/风险，不凑数。
            existing_code 是必须字段：从 diff 中【逐字】摘录发现所指认的新增/修改代码（1-5 行，去掉 +/- 前缀，保留原样缩进）。
            系统将用它确定性校准行号，lines 给出你的估计即可；无法给出摘录的泛泛之谈不要上报。
            """;

    /**
     * 过滤 pass 的系统提示。不对称原则是它的灵魂：删除一条正确发现 = 静默销毁一个真实缺陷，
     * 留下一条错误发现 = 几秒钟注意力——所以证据不足一律放行，只删"diff 能证明错误"的。
     */
    private static final String FILTER_SYSTEM_PROMPT = """
            你是代码审查发现的事实核查员。这些发现来自一个能读取代码库上下文的审查引擎；你只能看到它审查的 diff。你看不到的上下文它可能看过。
            你的任务很窄：只删除这份 diff 能【证明】事实错误的发现——要么它指认的代码不在 diff 中，要么 diff 中的某一行与它的核心论断直接矛盾。你不评判发现是否有用、优先级高低、值不值得审查者的时间。
            两种错误的代价不对等：
            - 留下一条错误发现，审查者花几秒钟注意力；
            - 删掉一条正确发现，真实缺陷被静默销毁，永远无人知晓它被丢弃。
            因此证据不足时一律放行。"可疑"、"我无法验证"、"价值低"、"被指认的代码看起来没问题"、"我不会这么报"都等于放行。
            内存安全、并发、链接一致性、未使用参数、行为变化类的发现，除非 diff 能直接证明其错误，否则一律放行。
            只输出一个 JSON 对象，不要 markdown 包裹：{"analysis":[{"id":"f1","verdict":"keep|remove","reason":"..."}],"remove_ids":["f1"]}
            先在 analysis 中逐条完整推理每个候选，再在 remove_ids 中列出所有判定为 remove 的 id；没有可删除的就输出 {"analysis":[{"id":"f1","verdict":"keep","reason":"..."}],"remove_ids":[]}。
            """;

    /** 宽限轮指令：解析失败后的最后一次机会（OCR grace round 思路的单调用化）。 */
    private static final String GRACE_INSTRUCTION = """
            \n\n上一次输出无法解析为 JSON。这是最后一次机会：只输出一个 JSON 对象 {"findings":[...]}，不要 markdown 包裹、不要任何解释或思维链；如果没有发现就输出 {"findings":[]}。""";

    /** 每组确认发现数的硬上限：到达即停后续轮次（OCR confirmedCap 同款）。 */
    private static final int CONFIRMED_CAP = 40;
    /** 每组最多文件数（OCR maxFilesPerGroup 同款）：组是上下文单元，过大等于回到整包 prompt。 */
    private static final int MAX_FILES_PER_GROUP = 10;
    /** 分组预算 = 单文件预算 × 2：组内通常 1-2 个大文件或一批小文件。 */
    private static final long GROUP_TOKEN_FACTOR = 2;

    /** 内建密钥路径名单：这类文件的内容不进 prompt，跳审并留痕（OCR secret_path 同款）。
     *  package 可见——delegate 规格书（ReviewSpecBuilder）使用同一份名单，口径永远一致。 */
    static final List<String> SECRET_PATH_GLOBS = List.of(
            "**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx",
            "**/*.jks", "**/*.keystore", "**/id_rsa*", "**/id_dsa*", "**/id_ecdsa*",
            "**/id_ed25519*", "**/credentials*.json", "**/*_credentials.json", "**/.npmrc");

    /** 进程级共享 HttpClient：连接建立上限 15s；HttpClient 不可变且线程安全。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 看门狗共享调度池：单守护线程足够——每回合只有两个轻量定时任务。 */
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
    private final boolean reviewFilter;
    private final int reviewRounds;
    private final int reviewConcurrency;
    private final long maxFileTokens;
    private final boolean resume;

    public BuiltinReviewEngine(BlobStore blobStore, Duration totalTimeout, Duration idleTimeout,
                               String providerId, String modelName, String baseUrl, String apiKey,
                               Long maxTokens) {
        this(blobStore, totalTimeout, idleTimeout, providerId, modelName, baseUrl, apiKey,
                maxTokens, null, null, null, null, null);
    }

    /** 完整构造器：filter/轮次/并发/单文件预算/续审为 null 时取各自默认（见 EngineConfig 归一）。 */
    public BuiltinReviewEngine(BlobStore blobStore, Duration totalTimeout, Duration idleTimeout,
                               String providerId, String modelName, String baseUrl, String apiKey,
                               Long maxTokens, Boolean reviewFilter, Integer reviewRounds,
                               Integer reviewConcurrency, Long maxFileTokens, Boolean resume) {
        this.blobStore = blobStore;
        this.totalTimeout = totalTimeout;
        this.idleTimeout = idleTimeout;
        this.providerId = providerId;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.reviewFilter = reviewFilter == null || reviewFilter;
        this.reviewRounds = reviewRounds == null ? 1 : Math.max(1, Math.min(8, reviewRounds));
        this.reviewConcurrency = reviewConcurrency == null ? 2 : Math.max(1, Math.min(8, reviewConcurrency));
        this.maxFileTokens = maxFileTokens == null ? 24_000L : Math.max(1_000L, maxFileTokens);
        this.resume = resume == null || resume;
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(ENGINE_ID, "v2", "builtin:chat.completions", providerId, modelName);
    }

    @Override
    public ReviewEvidence review(ReviewRequest request) {
        EngineDescriptor descriptor = describe();
        // 取消早退：任务被取消后再发起新的 LLM 调用纯属烧钱。
        if (Thread.currentThread().isInterrupted()) {
            return new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "aborted by task cancellation (before connect)", -1);
        }
        ReviewSessionLog log = new ReviewSessionLog();
        try {
            ReviewEvidence evidence = orchestrate(request, descriptor, log);
            log.write(blobStore, request.ticketNo(), request.reviewRound());
            return evidence;
        } catch (TurnException e) {
            log.terminalFailure(e.failure.kind(), e.failure.detail());
            log.write(blobStore, request.ticketNo(), request.reviewRound());
            return e.failure;
        } catch (Throwable t) {
            EngineFailure failure = new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "gate-engine failed: " + t, -1);
            log.terminalFailure(failure.kind(), failure.detail());
            log.write(blobStore, request.ticketNo(), request.reviewRound());
            return failure;
        }
    }

    // ────────────────────────────── 编排：闸门 → 分组 → 续审 → 多轮 → 过滤 ──────────────────────────────

    private ReviewEvidence orchestrate(ReviewRequest request, EngineDescriptor descriptor,
                                       ReviewSessionLog log) {
        Instant started = Instant.now();
        ReviewRules rules = ReviewRules.load(request.cloneRepo() == null ? null : request.cloneRepo().path());
        List<DiffSections.Section> sections = DiffSections.split(request.snapshot().diff());

        Set<String> changed = new LinkedHashSet<>(request.snapshot().changedPaths());
        Set<String> changedSeen = new LinkedHashSet<>();
        List<SkippedPath> skipped = new ArrayList<>();
        List<DiffSections.Section> reviewable = new ArrayList<>();
        for (DiffSections.Section s : sections) {
            changedSeen.add(s.path());
            String reason = skipReason(s, rules);
            if (reason != null) {
                skipped.add(new SkippedPath(s.path(), reason));
            } else {
                reviewable.add(s);
            }
        }
        // diff 未含但 changed 声明的路径（理论不可达，防御性）：按旧口径计入覆盖，不留静默缺口。
        Set<String> covered = new LinkedHashSet<>();
        for (String p : changed) {
            if (!changedSeen.contains(p)) {
                covered.add(p);
            }
        }

        if (reviewable.isEmpty()) {
            // 全部路径被授权跳过：无调用可发，报告如实记录（无发现的空报告不是 pass——判决仍由策略推导）。
            BlobRef rawRef = blobStore.put(noReviewableRaw(request, skipped).getBytes(StandardCharsets.UTF_8),
                    rawName(request, "builtin.json"));
            log.header(request.ticketNo(), request.reviewRound(), request.snapshot().treeHash().hex(),
                    providerId, modelName);
            log.terminalReport(0, 0, 0);
            return new EngineReport(descriptor, request.snapshot().treeHash().hex(),
                    List.of(), covered, skipped, false, rawRef, 0,
                    Duration.between(started, Instant.now()), null, null, null, List.of());
        }

        List<List<DiffSections.Section>> groups = group(reviewable, maxFileTokens * GROUP_TOKEN_FACTOR);
        log.header(request.ticketNo(), request.reviewRound(), request.snapshot().treeHash().hex(),
                providerId, modelName);

        // 续审（P2-2）：上一次尝试以失败告终且模型一致时，按组指纹复用已完成组的发现，
        // 只重新派发失败/缺失的组。成功尝试永不复用——显式重审同一轮就是要求全新审查。
        Map<String, List<Finding>> reusable = Map.of();
        String priorModel = null;
        if (resume) {
            ReviewSessionLog prior = ReviewSessionLog.read(blobStore, request.ticketNo(), request.reviewRound());
            if (prior.wasFailedAttempt() && modelName.equals(prior.modelName())) {
                reusable = prior.completedGroupsByFingerprint();
                priorModel = prior.modelName();
            }
        }

        Usage usage = new Usage();
        List<Finding> findings = new ArrayList<>();
        List<Finding> filteredOut = new ArrayList<>();
        boolean degraded = false;
        BlobRef firstRaw = null;
        int reusedCount = 0;
        List<Integer> dispatchIndices = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            String fingerprint = ReviewSessionLog.sha256Hex(concatBodies(groups.get(gi)));
            List<Finding> priorFindings = reusable.get(fingerprint);
            if (priorFindings != null) {
                findings.addAll(priorFindings);
                log.groupReused(gi, fingerprint, priorFindings, priorModel);
                reusedCount++;
            } else {
                dispatchIndices.add(gi);
            }
        }
        int dispatchedCount = dispatchIndices.size();

        java.util.concurrent.ExecutorService pool =
                Executors.newFixedThreadPool(Math.max(1, Math.min(reviewConcurrency, dispatchedCount)),
                        r -> {
                            Thread t = new Thread(r, "gate-engine-review-" + request.ticketNo());
                            t.setDaemon(true);
                            return t;
                        });
        try {
            List<java.util.concurrent.Future<GroupResult>> futures = new ArrayList<>();
            for (int di = 0; di < dispatchIndices.size(); di++) {
                final int gi = dispatchIndices.get(di);
                final String fingerprint = ReviewSessionLog.sha256Hex(concatBodies(groups.get(gi)));
                final List<DiffSections.Section> group = groups.get(gi);
                futures.add(pool.submit(() -> reviewGroup(request, descriptor, rules, log, gi,
                        group, sections, groups.size(), fingerprint)));
            }
            for (int di = 0; di < dispatchIndices.size(); di++) {
                int gi = dispatchIndices.get(di);
                String fingerprint = ReviewSessionLog.sha256Hex(concatBodies(groups.get(gi)));
                GroupResult result;
                try {
                    result = futures.get(di).get();
                } catch (java.util.concurrent.CancellationException e) {
                    log.groupResult(gi, fingerprint, "failed", null, "CRASH");
                    throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                            "aborted by task cancellation", -1));
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof TurnException te) {
                        log.groupResult(gi, fingerprint, "failed", null, te.failure.kind().name());
                        throw te;
                    }
                    log.groupResult(gi, fingerprint, "failed", null, "CRASH");
                    throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                            "group review failed: " + cause.getMessage(), -1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.groupResult(gi, fingerprint, "failed", null, "CRASH");
                    throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                            "aborted by task cancellation", -1));
                }
                if (result.failure != null) {
                    log.groupResult(gi, fingerprint, "failed", null, result.failure.kind().name());
                    throw new TurnException(result.failure);
                }
                log.groupResult(gi, fingerprint, "completed", result.findings, null);
                findings.addAll(result.findings);
                filteredOut.addAll(result.filteredOut);
                usage.add(result);
                if (result.firstRaw != null && firstRaw == null) {
                    firstRaw = result.firstRaw;
                }
                degraded |= result.degraded;
            }
        } finally {
            pool.shutdownNow();
        }

        for (DiffSections.Section s : reviewable) {
            covered.add(s.path());
        }
        log.terminalReport(reusedCount, dispatchedCount, findings.size());
        return new EngineReport(descriptor, request.snapshot().treeHash().hex(),
                findings, covered, skipped, degraded, firstRaw, 0,
                Duration.between(started, Instant.now()),
                usage.promptTokens, usage.completionTokens, usage.totalTokens, filteredOut);
    }

    /**
     * 单文件的确定性跳过判定：返回 null 表示送审。顺序即优先级——删除/二进制是介质属性，
     * 密钥名单是安全属性，规则跳过是项目授权，超限是预算闸门。
     */
    private String skipReason(DiffSections.Section s, ReviewRules rules) {
        if (s.deleted()) {
            return SkippedPath.DELETED;
        }
        if (s.binary()) {
            return SkippedPath.BINARY;
        }
        for (String glob : SECRET_PATH_GLOBS) {
            if (ReviewRules.matches(glob, s.path())) {
                return SkippedPath.SECRET_PATH;
            }
        }
        if (rules.skip(s.path())) {
            return SkippedPath.RULE_SKIP;
        }
        if (DiffSections.estimateTokens(s.body()) > maxFileTokens) {
            return SkippedPath.TOO_LARGE;
        }
        return null;
    }

    /**
     * 确定性分组：按顶层目录聚簇（同目录 = 大概率同一关注点），簇内按路径序贪心切块，
     * 每块不超过组 token 预算与 {@link #MAX_FILES_PER_GROUP}。无 LLM 参与——分组必须可复现，
     * 语义分组留待将来以独立调用实现。
     */
    private static List<List<DiffSections.Section>> group(List<DiffSections.Section> sections, long maxGroupTokens) {
        List<DiffSections.Section> sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparing(DiffSections.Section::path));
        Map<String, List<DiffSections.Section>> buckets = new LinkedHashMap<>();
        for (DiffSections.Section s : sorted) {
            String p = s.path();
            int slash = p.indexOf('/');
            String dir = slash < 0 ? "" : p.substring(0, slash);
            buckets.computeIfAbsent(dir, k -> new ArrayList<>()).add(s);
        }
        List<List<DiffSections.Section>> groups = new ArrayList<>();
        for (List<DiffSections.Section> bucket : buckets.values()) {
            long tokens = 0;
            List<DiffSections.Section> current = new ArrayList<>();
            for (DiffSections.Section s : bucket) {
                long t = DiffSections.estimateTokens(s.body());
                if (!current.isEmpty() && (current.size() >= MAX_FILES_PER_GROUP
                        || tokens + t > maxGroupTokens)) {
                    groups.add(current);
                    current = new ArrayList<>();
                    tokens = 0;
                }
                current.add(s);
                tokens += t;
            }
            if (!current.isEmpty()) {
                groups.add(current);
            }
        }
        return groups;
    }

    // ────────────────────────────── 组内：多轮 + 宽限 + 过滤 ──────────────────────────────

    /** 一组审查的产出：发现、被过滤掉的原发现（证据留痕）、用量与首个原始输出 blob。
     *  用量字段为可空 Long：该组所有调用都没回报 usage 时保持 null（与 unavailable 语义一致）。 */
    private record GroupResult(List<Finding> findings, List<Finding> filteredOut,
                               Long promptTokens, Long completionTokens, Long totalTokens,
                               BlobRef firstRaw, boolean degraded, EngineFailure failure) {

        static GroupResult fail(EngineFailure f) {
            return new GroupResult(List.of(), List.of(), null, null, null, null, false, f);
        }
    }

    private GroupResult reviewGroup(ReviewRequest request, EngineDescriptor descriptor, ReviewRules rules,
                                    ReviewSessionLog log, int groupIndex, List<DiffSections.Section> group,
                                    List<DiffSections.Section> allSections, int groupCount, String fingerprint) {
        String groupDiff = concatBodies(group);
        String otherFiles = otherFileNames(group, allSections);
        String rulesText = rules.renderFor(new LinkedHashSet<>(pathsOf(group)));

        Usage usage = new Usage();
        List<Finding> confirmed = new ArrayList<>();
        List<Finding> filteredOut = new ArrayList<>();
        boolean degraded = false;
        BlobRef firstRaw = null;
        int consecutiveUnparseable = 0;

        for (int round = 1; round <= reviewRounds; round++) {
            String userPrompt = buildUserPrompt(request, groupIndex, groupCount, group,
                    otherFiles, rulesText, round, confirmed);
            String blobName = groupIndex == 0 && round == 1 ? "builtin.json" : "g" + groupIndex + "-r" + round + ".json";
            LlmTurn turn = callLlm(log, "main", groupIndex, round, request, descriptor,
                    SYSTEM_PROMPT, userPrompt, rawName(request, blobName));
            usage.add(turn);
            if (firstRaw == null) {
                firstRaw = turn.rawRef;
            }

            PrismOutput out = tryParseFindings(turn.content());
            if (out == null) {
                // 失败连击：连续第二次解析失败直接判 UNPARSEABLE，不再给重试空间（OCR 工程数据：
                // 无上限的换措辞重试曾把同一发现重发 6 次）。第一次失败给一次宽限轮。
                if (consecutiveUnparseable >= 1) {
                    return GroupResult.fail(new EngineFailure(descriptor,
                            EngineFailure.FailureKind.UNPARSEABLE,
                            "engine JSON unparseable two rounds in a row; raw persisted at "
                                    + turn.rawRef().relPath() + " (bytes=" + turn.content().length() + ")", -1));
                }
                consecutiveUnparseable++;
                String graceName = (groupIndex == 0 && round == 1 ? "builtin" : "g" + groupIndex + "-r" + round)
                        + ".grace.json";
                LlmTurn grace = callLlm(log, "grace", groupIndex, round, request, descriptor,
                        SYSTEM_PROMPT, userPrompt + GRACE_INSTRUCTION, rawName(request, graceName));
                usage.add(grace);
                out = tryParseFindings(grace.content());
                if (out == null) {
                    return GroupResult.fail(new EngineFailure(descriptor,
                            EngineFailure.FailureKind.UNPARSEABLE,
                            "engine JSON unparseable after grace round; raw persisted at "
                                    + grace.rawRef().relPath() + " (bytes=" + grace.content().length() + ")", -1));
                }
                turn = grace;
            }
            consecutiveUnparseable = 0;

            boolean[] unknownSeverity = {false};
            List<Finding> roundFindings = toFindings(out, allSections, unknownSeverity);
            degraded |= unknownSeverity[0];

            // 每轮过滤只裁剪本轮新增（per-round isolation）：确认过的发现不再反复送审。
            if (reviewFilter && !roundFindings.isEmpty()) {
                FilterOutcome fo = runFilter(request, descriptor, log, groupIndex, round,
                        roundFindings, groupDiff, usage);
                roundFindings = fo.kept();
                filteredOut.addAll(fo.removed());
            }
            confirmed.addAll(roundFindings);

            // 提前停：本轮零新发现（含第 1 轮）说明模型已经交底，多轮只剩烧钱；到达确认上限同理。
            if (roundFindings.isEmpty() || confirmed.size() >= CONFIRMED_CAP) {
                break;
            }
        }
        return new GroupResult(confirmed, filteredOut, usage.promptTokens, usage.completionTokens,
                usage.totalTokens, firstRaw, degraded, null);
    }

    /**
     * 过滤 pass（宁留勿删）。它是精度优化而非安全控制：任何失败（超时/解析失败/上游错误）都
     * fail-open 保留全部候选——过滤器绝不能成为丢发现的黑洞，更不能阻塞审查。
     */
    private FilterOutcome runFilter(ReviewRequest request, EngineDescriptor descriptor,
                                    ReviewSessionLog log, int groupIndex, int round,
                                    List<Finding> candidates, String groupDiff, Usage usage) {
        try {
            String findingsJson = filterCandidatesJson(candidates);
            String userPrompt = "以下发现由审查引擎产出，请核查。\n\n### 候选发现\n" + findingsJson
                    + "\n\n### 被审查的 diff\n" + groupDiff;
            String blobName = (groupIndex == 0 && round == 1 ? "builtin" : "g" + groupIndex + "-r" + round)
                    + ".filter.json";
            LlmTurn turn = callLlm(log, "filter", groupIndex, round, request, descriptor,
                    FILTER_SYSTEM_PROMPT, userPrompt, rawName(request, blobName));
            usage.add(turn);
            List<String> removeIds = parseRemoveIds(turn.content);
            if (removeIds == null || removeIds.isEmpty()) {
                return new FilterOutcome(candidates, List.of());
            }
            Set<String> remove = Set.copyOf(removeIds);
            List<Finding> kept = new ArrayList<>();
            List<Finding> removed = new ArrayList<>();
            for (Finding f : candidates) {
                if (f.ruleId() != null && remove.contains(f.ruleId())) {
                    removed.add(f);
                } else {
                    kept.add(f);
                }
            }
            return new FilterOutcome(kept, removed);
        } catch (TurnException e) {
            return new FilterOutcome(candidates, List.of());
        } catch (RuntimeException e) {
            return new FilterOutcome(candidates, List.of());
        }
    }

    private record FilterOutcome(List<Finding> kept, List<Finding> removed) {
    }

    // ────────────────────────────── LLM 调用（SSE 机器 + 看门狗） ──────────────────────────────

    /** 一次成功收尾的 LLM 调用：原始输出已落 blob，usage 随行。 */
    private record LlmTurn(String content, Long promptTokens, Long completionTokens, Long totalTokens,
                           BlobRef rawRef) {
    }

    /** 用量累加器：所有调用（审查/宽限/过滤）求和后进报告。任何调用都没回报 usage 时保持 null——
     *  「上游没给」与「用了 0 个」必须可区分（tokenSource=unavailable 语义依赖它）。 */
    private static final class Usage {
        Long promptTokens;
        Long completionTokens;
        Long totalTokens;

        void add(LlmTurn t) {
            promptTokens = plus(promptTokens, t.promptTokens());
            completionTokens = plus(completionTokens, t.completionTokens());
            totalTokens = plus(totalTokens, t.totalTokens());
        }

        void add(GroupResult r) {
            promptTokens = plus(promptTokens, r.promptTokens());
            completionTokens = plus(completionTokens, r.completionTokens());
            totalTokens = plus(totalTokens, r.totalTokens());
        }

        private static Long plus(Long base, Long v) {
            if (v == null) {
                return base;
            }
            return base == null ? v : base + v;
        }
    }

    /** 携带 EngineFailure 值的控制流异常：所有内部层用它上抛，review() 统一落值为证据。 */
    private static final class TurnException extends RuntimeException {
        final EngineFailure failure;

        TurnException(EngineFailure failure) {
            super(failure.detail(), null, false, false);
            this.failure = failure;
        }
    }

    private LlmTurn callLlm(ReviewSessionLog log, String phase, int groupIndex, int round,
                            ReviewRequest request, EngineDescriptor descriptor,
                            String systemPrompt, String userPrompt, String blobName) {
        Instant started = Instant.now();
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(jsonEscape(modelName)).append("\",\"stream\":true")
                .append(",\"stream_options\":{\"include_usage\":true}");
        if (maxTokens != null) {
            body.append(",\"max_tokens\":").append(maxTokens);
        }
        body.append(",\"messages\":[{\"role\":\"system\",\"content\":").append(jsonLiteral(systemPrompt))
                .append("},{\"role\":\"user\",\"content\":").append(jsonLiteral(userPrompt))
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "aborted by task cancellation", -1));
        } catch (Exception e) {
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream connect failed: " + e.getMessage(), -1));
        }
        if (upstream.statusCode() / 100 != 2) {
            String snippet;
            try (InputStream in = upstream.body()) {
                snippet = new String(in.readNBytes(1024), StandardCharsets.UTF_8).replace('\n', ' ');
            } catch (Exception e) {
                snippet = "<unable to read error body>";
            }
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "upstream HTTP " + upstream.statusCode() + ": " + snippet.trim(), -1));
        }

        SseResult result = consumeSse(request, descriptor, upstream, started);
        // 原样保存（不做任何清洗/截断），后续无论成功或失败都可核对原始应答。
        BlobRef rawRef = blobStore.put(result.content().getBytes(StandardCharsets.UTF_8), blobName);
        // 逐请求留痕（P2-1）：prompt 原文 + 响应 + usage + 耗时 + 阶段，过程可回放。
        log.llmCall(phase, groupIndex, round, systemPrompt, userPrompt, result.content(),
                result.promptTokens(), result.completionTokens(), result.totalTokens(),
                Duration.between(started, Instant.now()).toMillis(), rawRef);
        return new LlmTurn(result.content(), result.promptTokens(), result.completionTokens(),
                result.totalTokens(), rawRef);
    }

    private record SseResult(String content, Long promptTokens, Long completionTokens, Long totalTokens) {
    }

    /** 消费 SSE 流：读循环跑在专用 reader 线程上，调用线程按自适应期限盯 Future。 */
    private SseResult consumeSse(ReviewRequest request, EngineDescriptor descriptor,
                                 HttpResponse<InputStream> upstream, Instant started) {
        // 看门狗到期时置位触发窗口名；reader 据此把 IOException 分类为 TIMEOUT。
        AtomicReference<String> firedWindow = new AtomicReference<>(null);
        // reader 每收到一个数据事件就刷新该时刻；调用方据此推算「idle 截止」，即使底层
        // close() 唤不醒阻塞中的 readLine（个别 JDK HttpClient 栈行为），也能按时判决。
        java.util.concurrent.atomic.AtomicLong lastDataAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        InputStream stream = upstream.body();

        FutureTask<SseResult> reader = new FutureTask<>(() ->
                readSseLoop(descriptor, stream, started, firedWindow, lastDataAt));
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
                    throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                            (window == null ? (now >= totalDeadline ? "total" : "idle") : window)
                                    + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                    + "s (deadline enforced by caller)",
                            -1));
                }
                try {
                    return reader.get(deadline - now, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException notYet) {
                    // 期限已到但 reader 可能刚好在最后时刻推进了 lastDataAt——重算后最多再等一轮。
                }
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TurnException te) {
                throw te;
            }
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream reader failed: " + cause.getMessage(), -1));
        } catch (InterruptedException e) {
            // 任务取消（TaskRunner 中断调用线程）：不再烧上游 token，立即返回。
            Thread.currentThread().interrupt();
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "aborted by task cancellation", -1));
        } finally {
            // 无论结果如何都释放：中断 reader（取消即中断）并关流。
            reader.cancel(true);
            closeQuietly(stream);
        }
    }

    /** reader 线程的读循环主体；返回内容与 usage，失败以 TurnException 落值。 */
    private SseResult readSseLoop(EngineDescriptor descriptor, InputStream stream, Instant started,
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
                        throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                                window + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                        + "s (" + content.length() + " chars in)", -1));
                    }
                    throw e;
                }
                if (line == null) {
                    break;   // EOF：部分网关不发 [DONE] 哨兵，允许正常收尾
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                            "aborted by task cancellation (" + content.length() + " chars in)", -1));
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
                        throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                                "upstream inline error: " + truncate(msg == null ? String.valueOf(err) : String.valueOf(msg), 1024),
                                -1));
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
        } catch (TurnException e) {
            throw e;
        } catch (Exception e) {
            String window = firedWindow.get();
            if (window != null) {
                throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.TIMEOUT,
                        window + " timeout after " + Duration.between(started, Instant.now()).toSeconds()
                                + "s (" + content.length() + " chars in)", -1));
            }
            throw new TurnException(new EngineFailure(descriptor, EngineFailure.FailureKind.CRASH,
                    "stream crashed: " + e.getMessage(), -1));
        } finally {
            totalHandle.cancel(false);
            if (idleHandle[0] != null) {
                idleHandle[0].cancel(false);
            }
            closeQuietly(stream);
        }
        return new SseResult(content.toString(), promptTokens, completionTokens, totalTokens);
    }

    // ────────────────────────────── 解析、回锚与过滤判定 ──────────────────────────────

    /** 解析审查输出；任何结构问题返回 null（由调用方决定宽限或失败）。 */
    private static PrismOutput tryParseFindings(String rawContent) {
        String raw = stripForParse(rawContent);
        try {
            PrismOutput out = PrismJson.parse(raw);
            return out.findings == null ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 引擎输出 → 领域发现：字段沉降规则与旧适配器一致（未知 severity → BLOCKER + degraded），
     * 新增两步确定性收尾——existing_code 回锚校准行号；无 id 的发现合成稳定 id 供过滤器引用。
     */
    private static List<Finding> toFindings(PrismOutput out, List<DiffSections.Section> sections,
                                            boolean[] unknownSeverity) {
        List<Finding> findings = new ArrayList<>();
        int synthesized = 0;
        for (PrismFinding pf : out.findings) {
            Severity sev = mapSeverity(pf.severity);
            if (sev == Severity.BLOCKER && !"high".equalsIgnoreCase(pf.severity)) {
                unknownSeverity[0] = true;   // 与原适配器同款沉降规则：未知词 → BLOCKER + degraded
            }
            String path = firstLocationPath(pf);
            int[] lines = firstLocationLines(pf);
            Integer lineStart = lines == null ? null : lines[0];
            Integer lineEnd = lines == null ? null : lines[1];
            int[] anchored = FindingAnchor.anchor(pf.existingCode, path, sections);
            if (anchored != null) {
                lineStart = anchored[0];
                lineEnd = anchored[1];
            }
            String message = pf.title == null || pf.title.isBlank()
                    ? (pf.message == null ? "" : pf.message)
                    : (pf.message == null || pf.message.isBlank() ? pf.title : pf.title + ": " + pf.message);
            String id = pf.id == null || pf.id.isBlank() ? "f" + (++synthesized) : pf.id;
            findings.add(new Finding(sev, pf.severity == null ? "" : pf.severity,
                    path, lineStart, lineEnd, id, message, pf.suggestion,
                    pf.existingCode == null ? null : pf.existingCode.trim()));
        }
        return findings;
    }

    /** 过滤判定解析：任何结构问题返回 null → fail-open 保留全部候选。 */
    private static List<String> parseRemoveIds(String rawContent) {
        String raw = stripForParse(rawContent);
        try {
            Map<String, Object> obj = PrismJson.parseObjectMap(raw);
            if (!(obj.get("remove_ids") instanceof List<?> list)) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    ids.add(s);
                }
            }
            return ids;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 过滤候选的最小 JSON：id 与 existing_code 是核查锚点，行号仅参考。 */
    private static String filterCandidatesJson(List<Finding> candidates) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            Finding f = candidates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(jsonLiteral(f.ruleId() == null ? "f" + (i + 1) : f.ruleId()))
                    .append(",\"path\":").append(jsonLiteral(f.path()))
                    .append(",\"lines\":{\"start\":").append(f.lineStart() == null ? 0 : f.lineStart())
                    .append(",\"end\":").append(f.lineEnd() == null ? 0 : f.lineEnd()).append('}')
                    .append(",\"existing_code\":").append(jsonLiteral(f.existingCode() == null ? "" : f.existingCode()))
                    .append(",\"message\":").append(jsonLiteral(f.message()))
                    .append(",\"suggestion\":").append(jsonLiteral(f.suggestion() == null ? "" : f.suggestion()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    // ────────────────────────────── Prompt 组装 ──────────────────────────────

    private static String buildUserPrompt(ReviewRequest request, int groupIndex, int groupCount,
                                          List<DiffSections.Section> group, String otherFiles,
                                          String rulesText, int round, List<Finding> confirmed) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单 ").append(request.ticketNo()).append(" · 第 ").append(request.reviewRound())
                .append(" 轮 · 审查组 ").append(groupIndex + 1).append('/').append(groupCount)
                .append("（").append(group.size()).append(" 个文件）\n");
        if (request.ticketContext() != null && !request.ticketContext().isBlank()) {
            sb.append("\n## 工单背景（审查时对照需求）\n").append(request.ticketContext().trim()).append('\n');
        }
        if (!otherFiles.isEmpty()) {
            sb.append("\n## 其他变更文件（不在本组，不要求审查，仅供交叉参考）\n").append(otherFiles).append('\n');
        }
        if (!rulesText.isEmpty()) {
            sb.append("\n## 审查规则（项目配置，适用文件已标注）\n").append(rulesText).append('\n');
        }
        if (round > 1 && !confirmed.isEmpty()) {
            sb.append("\n## 已确认的发现（此前轮次已报告——不要重复上报，只找【新】问题）\n");
            int i = 1;
            for (Finding f : confirmed) {
                sb.append(i++).append(". [").append(f.severity()).append("] ")
                        .append(f.path())
                        .append(f.lineStart() == null ? "" : ":" + f.lineStart())
                        .append(' ').append(f.message()).append('\n');
            }
        }
        sb.append("\n## 本组待审变更（unified diff）\n\n").append(concatBodies(group));
        return sb.toString();
    }

    private static String concatBodies(List<DiffSections.Section> sections) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(sections.get(i).body());
        }
        return sb.toString();
    }

    /** 本组之外的其余可审文件名（OCR other_changed_files 惯例：给交叉参考，不扩审查职责）。 */
    private static String otherFileNames(List<DiffSections.Section> group, List<DiffSections.Section> all) {
        Set<String> inGroup = new LinkedHashSet<>(pathsOf(group));
        StringBuilder sb = new StringBuilder();
        for (DiffSections.Section s : all) {
            if (inGroup.contains(s.path())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(s.path());
        }
        return sb.toString();
    }

    private static List<String> pathsOf(List<DiffSections.Section> sections) {
        List<String> paths = new ArrayList<>();
        for (DiffSections.Section s : sections) {
            paths.add(s.path());
        }
        return paths;
    }

    private static String rawName(ReviewRequest request, String name) {
        return "raw/" + request.ticketNo() + "/" + request.reviewRound() + "/" + name;
    }

    private static String noReviewableRaw(ReviewRequest request, List<SkippedPath> skipped) {
        StringBuilder sb = new StringBuilder("no reviewable files in this round\n");
        sb.append("ticket=").append(request.ticketNo()).append(" round=").append(request.reviewRound())
                .append(" tree=").append(request.snapshot().treeHash().hex()).append('\n');
        for (SkippedPath sp : skipped) {
            sb.append("skipped ").append(sp.reason()).append(' ').append(sp.path()).append('\n');
        }
        return sb.toString();
    }

    // ────────────────────────────── 基础工具 ──────────────────────────────

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
