package gate.domain.config;

import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import java.nio.file.Path;
import java.util.List;

/**
 * Effective gate configuration (架构落地执行文档 §10.1).
 *
 * <p>Loaded from a single {@code gate.toml} carrying a {@code schema_version}; unknown keys refuse
 * startup, because fail-closed applies to configuration too.
 *
 * @param targetRefWhitelist the only refs the gate will ever authorise; also baked into the hook
 * @param engine             empty in P1. Engine-related preflight checks are skipped while absent
 *                           and become mandatory in P2 when prism is wired in.
 * @param web                Web operations console binding + auth (执行文档-后端-web §3, §8). Nullable
 *                           for the CLI/MCP paths that never start the HTTP server; only gate-web
 *                           requires it non-null.
 * @param session            Agent session orchestration defaults (执行文档-后端-web §5, §8). Nullable
 *                           on the CLI/MCP paths.
 * @param agent              Agent config defaults (执行文档-后端-web §8). Nullable on the CLI/MCP paths.
 * @param publishIdentity    发布提交身份覆盖（[publish_identity]）；null/空 = 自动取本机 git 作者。
 */
public record GateConfig(
        int schemaVersion,
        String project,
        Path authRepo,
        Path clonesRoot,
        List<String> targetRefWhitelist,
        Path gateHome,
        Path approvalsDir,
        Path dbPath,
        Path blobRoot,
        Path auditPath,
        Path locksDir,
        Path indexDir,
        CommitIdentity gateIdentity,
        Policy policy,
        EngineConfig engine,
        WebConfig web,
        SessionConfig session,
        AgentConfigDefaults agent,
        PublishIdentity publishIdentity) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    /**
     * Back-compatible 15-arg constructor for the existing CLI/MCP call sites (P1-P4) that carry no
     * web/session/agent configuration. Passes null for the three web-iteration blocks.
     */
    public GateConfig(int schemaVersion, String project, Path authRepo, Path clonesRoot,
                      List<String> targetRefWhitelist, Path gateHome, Path approvalsDir, Path dbPath,
                      Path blobRoot, Path auditPath, Path locksDir, Path indexDir,
                      CommitIdentity gateIdentity, Policy policy, EngineConfig engine) {
        this(schemaVersion, project, authRepo, clonesRoot, targetRefWhitelist, gateHome, approvalsDir,
                dbPath, blobRoot, auditPath, locksDir, indexDir, gateIdentity, policy, engine,
                null, null, null, null);
    }

    /** Back-compatible 18-arg constructor from before [publish_identity] existed. */
    public GateConfig(int schemaVersion, String project, Path authRepo, Path clonesRoot,
                      List<String> targetRefWhitelist, Path gateHome, Path approvalsDir, Path dbPath,
                      Path blobRoot, Path auditPath, Path locksDir, Path indexDir,
                      CommitIdentity gateIdentity, Policy policy, EngineConfig engine,
                      WebConfig web, SessionConfig session, AgentConfigDefaults agent) {
        this(schemaVersion, project, authRepo, clonesRoot, targetRefWhitelist, gateHome, approvalsDir,
                dbPath, blobRoot, auditPath, locksDir, indexDir, gateIdentity, policy, engine,
                web, session, agent, null);
    }

    public GateConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported gate.toml schema_version=" + schemaVersion
                    + ", expected " + CURRENT_SCHEMA_VERSION);
        }
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project must not be blank");
        }
        if (targetRefWhitelist == null || targetRefWhitelist.isEmpty()) {
            throw new IllegalArgumentException("target_ref whitelist must not be empty");
        }
        for (String ref : targetRefWhitelist) {
            if (!ref.startsWith("refs/heads/")) {
                throw new IllegalArgumentException("target ref must be a full refs/heads/... name: " + ref);
            }
        }
        targetRefWhitelist = List.copyOf(targetRefWhitelist);
        if (gateIdentity == null) {
            throw new IllegalArgumentException("gate_identity must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        // null 统一归一为 AUTO，调用方无需逐处判空。
        publishIdentity = publishIdentity == null ? PublishIdentity.AUTO : publishIdentity;
    }

    public boolean webConfigured() {
        return web != null;
    }

    public String primaryTargetRef() {
        return targetRefWhitelist.get(0);
    }

    public boolean isWhitelisted(String ref) {
        return targetRefWhitelist.contains(ref);
    }

    public boolean engineConfigured() {
        return engine != null;
    }

    /**
     * Present only once the review engine is configured.
     *
     * <p><b>Single-engine design</b>：唯一的引擎是 gate 内建引擎（进程内直连 OpenAI 兼容
     * /chat/completions）。{@code kind} 缺省/空白一律归一为 {@link #KIND_GATE_ENGINE}，
     * 其余任何值在构造期直接抛错（fail-closed）；双轨时代的 prism 分支已删除。
     *
     * @param cmd                deprecated：prism 外部二进制时代的遗留键，仅为兼容旧 gate.toml
     *                           而保留字段，运行期不再使用
     * @param args               deprecated：同上
     * @param timeoutSeconds     本轮总墙钟上限（秒），由引擎看门狗强制生效
     * @param idleTimeoutSeconds 流式读帧的空闲失效窗口（秒）；null 归一为
     *                           {@link #DEFAULT_IDLE_TIMEOUT_SECONDS}
     * @param maxTokens          可选：注入请求体的输出上限；null 表示不注入（交上游默认）
     * @param reviewFilter       可选：过滤 pass（宁留勿删的事实核查）；null 归一为 true。
     *                           过滤器是精度优化，失败 fail-open 保留全部发现，绝不阻塞审查
     * @param reviewRounds       可选：组内审查轮次（1-8）；null 归一为 1（单轮，成本不涨）。
     *                           第 2 轮起回注已确认发现、只找新问题，一轮无新发现即停
     * @param reviewConcurrency  可选：分组审查并发度（1-8）；null 归一为 2
     * @param maxFileTokens      可选：单文件 diff 的 token 闸门（chars/4 估算，≥1000）；
     *                           超限文件确定性跳审并记入证据，策略据此转人工
     * @param resume             可选：续审开关；null 归一为 true。同一轮的上一次尝试以失败告终
     *                           且模型一致时，按组指纹复用已完成组的发现，只重派失败组；
     *                           成功尝试永不复用——显式重审同一轮就是要求全新审查
     */
    public record EngineConfig(String cmd, List<String> args, long timeoutSeconds, String providerId,
                               String model, String kind, Long idleTimeoutSeconds, Long maxTokens,
                               Boolean reviewFilter, Integer reviewRounds, Integer reviewConcurrency,
                               Long maxFileTokens, Boolean resume) {

        /** {@code kind} 的唯一合法值：gate 内建审查引擎。 */
        public static final String KIND_GATE_ENGINE = "gate-engine";

        /** 流式读帧的默认空闲失效窗口（秒）；窗口内无任何数据帧即判停流。 */
        public static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 90;

        /** review_filter / review_rounds / review_concurrency / max_file_tokens 的默认值。 */
        public static final boolean DEFAULT_REVIEW_FILTER = true;
        public static final int DEFAULT_REVIEW_ROUNDS = 1;
        public static final int DEFAULT_REVIEW_CONCURRENCY = 2;
        public static final long DEFAULT_MAX_FILE_TOKENS = 24_000L;

        /** Back-compatible 8-arg constructor（新键出现前的配置面）。 */
        public EngineConfig(String cmd, List<String> args, long timeoutSeconds, String providerId,
                            String model, String kind, Long idleTimeoutSeconds, Long maxTokens) {
            this(cmd, args, timeoutSeconds, providerId, model, kind, idleTimeoutSeconds, maxTokens,
                    null, null, null, null, null);
        }

        public EngineConfig {
            if (kind == null || kind.isBlank()) {
                kind = KIND_GATE_ENGINE;
            }
            kind = kind.trim().toLowerCase(java.util.Locale.ROOT);
            if (!KIND_GATE_ENGINE.equals(kind)) {
                throw new IllegalArgumentException(
                        "unknown engine.kind \"" + kind + "\"（唯一合法值：" + KIND_GATE_ENGINE + "）");
            }
            // deprecated 键：兼容旧配置保留字段，运行期不使用，也不做非空约束。
            args = args == null ? List.of() : List.copyOf(args);
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("engine.timeout must be positive");
            }
            if (idleTimeoutSeconds == null) {
                idleTimeoutSeconds = DEFAULT_IDLE_TIMEOUT_SECONDS;
            }
            if (idleTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("engine.idle_timeout_seconds must be positive");
            }
            if (maxTokens != null && maxTokens <= 0) {
                throw new IllegalArgumentException("engine.max_tokens must be positive");
            }
            if (reviewRounds != null && (reviewRounds < 1 || reviewRounds > 8)) {
                throw new IllegalArgumentException("engine.review_rounds must be within 1..8");
            }
            if (reviewConcurrency != null && (reviewConcurrency < 1 || reviewConcurrency > 8)) {
                throw new IllegalArgumentException("engine.review_concurrency must be within 1..8");
            }
            if (maxFileTokens != null && maxFileTokens < 1_000) {
                throw new IllegalArgumentException("engine.max_file_tokens must be >= 1000");
            }
        }

        /** 续审开关视图（null 归一前的原始值；运行期统一走此视图）。 */
        public boolean resumeEnabled() {
            return resume == null || resume;
        }

        /** 过滤 pass 开关（null 已在构造期归一前的原始值；运行期统一走此视图）。 */
        public boolean filterEnabled() {
            return reviewFilter == null ? DEFAULT_REVIEW_FILTER : reviewFilter;
        }

        public int rounds() {
            return reviewRounds == null ? DEFAULT_REVIEW_ROUNDS : reviewRounds;
        }

        public int concurrency() {
            return reviewConcurrency == null ? DEFAULT_REVIEW_CONCURRENCY : reviewConcurrency;
        }

        public long fileTokenGate() {
            return maxFileTokens == null ? DEFAULT_MAX_FILE_TOKENS : maxFileTokens;
        }
    }

    /**
     * Web operations console binding + auth (执行文档-后端-web §3.3, §8).
     *
     * <p>{@code bind} MUST be a loopback address; {@code 0.0.0.0} is rejected at startup (fail-closed
     * exit 22) — the console is a local driver over the gate, never network-exposed. {@code
     * allowedOrigins} is the Host/Origin whitelist that blocks DNS-rebinding. {@code humanTokenFile}
     * is where the bootstrap HUMAN-domain token is written (relative to gate_home unless absolute).
     */
    public record WebConfig(String bind, int port, List<String> allowedOrigins, Path humanTokenFile) {

        public WebConfig {
            if (bind == null || bind.isBlank()) {
                throw new IllegalArgumentException("web.bind must not be blank");
            }
            if ("0.0.0.0".equals(bind.trim()) || "::".equals(bind.trim())) {
                throw new IllegalArgumentException(
                        "web.bind must be a loopback address (127.0.0.1 / localhost / ::1); "
                                + "binding to " + bind + " is refused (执行文档-后端-web §3.3)");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("web.port out of range: " + port);
            }
            allowedOrigins = List.copyOf(allowedOrigins);
            if (allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("web.allowed_origins must not be empty");
            }
            if (humanTokenFile == null) {
                throw new IllegalArgumentException("web.human_token_file must not be null");
            }
        }
    }

    /** Agent session orchestration defaults (执行文档-后端-web §5, §8). */
    public record SessionConfig(int portRangeMin, int portRangeMax, String defaultCli, String defaultAgentConfig,
                                int startTimeoutSeconds) {

        public SessionConfig {
            if (portRangeMin <= 0 || portRangeMax > 65535 || portRangeMin > portRangeMax) {
                throw new IllegalArgumentException(
                        "session port range invalid: [" + portRangeMin + ", " + portRangeMax + "]");
            }
            // Cold boot of the agent CLI (opencode serve with plugins) can exceed 10s, so the health
            // wait must be generous and never hardcoded short (执行文档-后端-web §7.3/R1).
            if (startTimeoutSeconds < 5 || startTimeoutSeconds > 900) {
                throw new IllegalArgumentException(
                        "session.start_timeout_seconds out of range: " + startTimeoutSeconds);
            }
        }
    }

    /** Agent config defaults (执行文档-后端-web §8). */
    public record AgentConfigDefaults(String defaultModel, String defaultProvider, Path contextTemplate) {
    }

    /**
     * 发布提交身份覆盖：name/email 皆空 = 自动取本机 git 作者；要覆盖必须成对提供
     * （半截配置在加载期拒绝——匿名/无名邮件的提交没有意义）。
     */
    public record PublishIdentity(String name, String email) {

        public static final PublishIdentity AUTO = new PublishIdentity(null, null);

        public PublishIdentity {
            boolean hasName = name != null && !name.isBlank();
            boolean hasEmail = email != null && !email.isBlank();
            if (hasName != hasEmail) {
                throw new IllegalArgumentException(
                        "publish_identity must set name and email together (or neither for auto)");
            }
            name = hasName ? name.trim() : null;
            email = hasEmail ? email.trim() : null;
        }

        public boolean isAuto() {
            return name == null;
        }
    }
}
