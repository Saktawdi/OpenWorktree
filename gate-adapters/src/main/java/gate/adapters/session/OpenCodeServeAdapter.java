package gate.adapters.session;

import gate.adapters.io.AdapterLog;
import gate.adapters.io.ServePidRegistry;
import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.PermissionRequest;
import gate.domain.session.QuestionRequest;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.SessionUsage;
import gate.domain.session.TurnPart;
import gate.domain.session.ToolCall;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.domain.ticket.Ticket;
import gate.ports.store.AgentConfigRepository;
import gate.ports.store.CredentialRepository;
import gate.ports.session.AgentSessionPort;
import gate.ports.infra.Clock;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProjectRepository;
import gate.ports.store.SessionRepository;
import gate.ports.store.TicketStageChangeRepository;
import gate.ports.task.TaskRegistry;
import gate.ports.infra.TicketLockManager;
import gate.ports.store.TicketRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenCode serve adapter (执行文档-后端-web §5.3.1, ADR-12): talks to a local
 * {@code opencode serve} HTTP API on an allocated port.
 *
 * <p>Streaming architecture (mirrors how OpenChamber integrates OpenCode): prompts are fired with
 * {@code POST /session/{id}/prompt_async} which returns immediately, and the adapter keeps one
 * persistent upstream SSE reader per session attached to OpenCode's {@code GET /event} bus. Bus
 * events are mapped onto the port's {@link SessionStreamChunk} vocabulary, so browsers get true
 * token-level streaming plus live tool-call state through the existing listener/SSE pipeline:
 *
 * <pre>
 *   text.delta        -> ContentChunk      reasoning.delta   -> ThinkingChunk
 *   tool.called/...   -> ToolCallChunk     step-finish       -> UsageChunk
 *   session.status=idle -> DoneChunk       session.error     -> ErrorChunk
 * </pre>
 *
 * <p>The final assistant message is persisted from the {@code message.updated} completion snapshot
 * (with token usage written back to the ticket), replacing the former synchronous-response parsing.
 * The upstream reader reconnects with {@code Last-Event-ID} after drops and force-reconnects a
 * stalled stream, following the same recovery shape as OpenChamber's upstream-reader module.
 */
public final class OpenCodeServeAdapter implements AgentSessionPort {

    /** Force-reconnect an upstream whose stream has been silent longer than this. */
    static final long UPSTREAM_STALL_TIMEOUT_MS = 90_000L;
    /** Delay between upstream reconnect attempts after a drop. */
    static final long UPSTREAM_RECONNECT_DELAY_MS = 2_000L;
    /** Timeout for permission reply / list HTTP calls against the serve instance. */
    static final Duration PERMISSION_HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final ProcessRunner processRunner;
    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessions;
    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final TicketStageChangeRepository restarts;
    private final TaskRegistry tasks;
    private final TicketLockManager ticketLocks;
    private final Clock clock;
    private final PortAllocator ports;
    private final Duration startTimeout;
    private final String opencodeExecutable;
    /** Null = auto base sync disabled (legacy wirings/tests); set, every session start re-syncs the clone base (T-118). */
    private final gate.ports.git.BaseSynchronizer baseSynchronizer;
    // PINNED TO HTTP/1.1 — the default client is HTTP/2, and its h2c cleartext upgrade stalls against
    // opencode's (Bun) HTTP server: the listener is up ("server listening") but a /health GET never
    // resolves, so every session start failed with "did not become healthy" and leaked a serve process.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Map<Integer, Process> serveProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionPorts = new ConcurrentHashMap<>();
    private final Map<String, Upstream> upstreams = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<SessionStreamChunk>>> listeners = new ConcurrentHashMap<>();
    // 有进行中回合的 session id（入队即算运行，排队等待也算），用于顶栏 busy 统计；同一 session 重复 send 用计数避免误清除
    private final Map<String, AtomicInteger> inFlightCounts = new ConcurrentHashMap<>();
    // 回合已被 prompt_async 受理、且尚未收到回合终点（session.status=idle / session.error）的 session 集合。
    // busy 释放只允许发生在“受理之后出现的终点”上：连接快照、重连重放等陈旧 idle 帧因无受理记录而被忽略，
    // 否则它们会把新回合的 busy 标记提前清零（实际运行一个智能体、统计却返回 0 的根因）。
    private final Set<String> acceptedSinceRelease = ConcurrentHashMap.newKeySet();
    // Per-session pending permission asks: gateSessionId -> permissionId -> request. Mirrors
    // the serve instance's /permission snapshot so auto-allow and pre-send reject have a local
    // view even before the SSE permission.asked frame is replayed after a reconnect.
    private final Map<String, Map<String, PermissionRequest>> pendingPermissions = new ConcurrentHashMap<>();
    // Per-session pending question asks (question 工具): gateSessionId -> requestId -> request.
    // Same shape as pendingPermissions; the /question snapshot is merged in on read so a page
    // reload restores the interactive card while the agent waits for the answer.
    private final Map<String, Map<String, QuestionRequest>> pendingQuestions = new ConcurrentHashMap<>();
    private final AdapterLog log;
    private final ServePidRegistry pidRegistry;
    /**
     * gate.toml location for MCP provisioning (presubmit_create & co.). Null = provisioning
     * disabled (tests); sessions then start exactly as before this capability existed.
     */
    private final Path gateToml;
    /** Optional: re-mints an agent-domain token when a stale session's serve is resurrected. */
    private final CredentialRepository credentials;

    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds) {
        this(processRunner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                ports, opencodeExecutable, startTimeoutSeconds, AdapterLog.noop(), null);
    }

    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds,
                                 AdapterLog log) {
        this(processRunner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock,
                ports, opencodeExecutable, startTimeoutSeconds, log, null);
    }

    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds,
                                 AdapterLog log,
                                 ServePidRegistry pidRegistry) {
        this(processRunner, agentConfigs, sessions, tickets, null, null, tasks, ticketLocks, clock,
                ports, opencodeExecutable, startTimeoutSeconds, log, pidRegistry, null, null);
    }

    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds,
                                 AdapterLog log,
                                 ServePidRegistry pidRegistry,
                                 Path gateToml) {
        this(processRunner, agentConfigs, sessions, tickets, projects, null, tasks, ticketLocks, clock,
                ports, opencodeExecutable, startTimeoutSeconds, log, pidRegistry, gateToml, null);
    }

    /**
     * Full constructor with credentials: {@code credentials} is optional (null disables token
     * re-minting on resurrection — resumed serves then start without the gate MCP tools).
     */
    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TicketStageChangeRepository restarts,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds,
                                 AdapterLog log,
                                 ServePidRegistry pidRegistry,
                                 Path gateToml,
                                 CredentialRepository credentials) {
        this(processRunner, agentConfigs, sessions, tickets, projects, restarts, tasks, ticketLocks,
                clock, ports, opencodeExecutable, startTimeoutSeconds, log, pidRegistry, gateToml,
                credentials, null);
    }

    /**
     * Fullest constructor: {@code baseSynchronizer} re-syncs the clone base on every session start
     * (T-118 基座同步); null disables it (legacy wirings/tests).
     */
    public OpenCodeServeAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TicketStageChangeRepository restarts,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 PortAllocator ports,
                                 String opencodeExecutable,
                                 int startTimeoutSeconds,
                                 AdapterLog log,
                                 ServePidRegistry pidRegistry,
                                 Path gateToml,
                                 CredentialRepository credentials,
                                 gate.ports.git.BaseSynchronizer baseSynchronizer) {
        this.processRunner = processRunner;
        this.agentConfigs = agentConfigs;
        this.sessions = sessions;
        this.tickets = tickets;
        this.projects = projects;
        this.restarts = restarts;
        this.tasks = tasks;
        this.ticketLocks = ticketLocks;
        this.clock = clock;
        this.ports = ports;
        this.opencodeExecutable = opencodeExecutable;
        this.startTimeout = Duration.ofSeconds(startTimeoutSeconds);
        this.log = log == null ? AdapterLog.noop() : log;
        this.pidRegistry = pidRegistry == null ? new ServePidRegistry(null) : pidRegistry;
        this.gateToml = gateToml;
        this.credentials = credentials;
        this.baseSynchronizer = baseSynchronizer;
        this.pidRegistry.sweepOrphans();
        sweepRangeOrphans();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "opencode-session");
            t.setDaemon(true);
            return t;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opencode-event-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.watchdog.scheduleAtFixedRate(this::checkStalledUpstreams,
                UPSTREAM_STALL_TIMEOUT_MS, 15_000L, TimeUnit.MILLISECONDS);
    }

    @Override
    public AutoCloseable attachListener(String sessionId, Consumer<SessionStreamChunk> listener) {
        Set<Consumer<SessionStreamChunk>> set =
                listeners.computeIfAbsent(sessionId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        set.add(listener);
        Upstream up = upstreams.get(sessionId);
        if (up != null && !up.stopped) {
            long sinceDone = System.currentTimeMillis() - up.lastDoneAt;
            // A very fast turn can finish before the browser finishes subscribing; re-signal done
            // so its SSE does not spin until the client-side timeout.
            if (up.lastDoneAt > 0 && sinceDone < 2_500L) {
                try {
                    listener.accept(new SessionStreamChunk.DoneChunk(sessionId, up.cliSessionId, clock.now()));
                } catch (Exception ignored) {
                }
            }
        }
        // 快速失败错误补偿：send 在浏览器挂上 SSE 之前就失败时（如懒复活失败），ErrorChunk
        // 没有听众、ERROR 行也只随 history 快照以前端忽略的 message 帧重放——不补发的话
        // UI 会一直转圈到客户端看门狗超时。3 秒内新挂的监听者补收一次。
        RecentError recent = recentErrors.get(sessionId);
        if (recent != null && System.currentTimeMillis() - recent.at() < 3_000L) {
            recentErrors.remove(sessionId);
            try {
                listener.accept(new SessionStreamChunk.ErrorChunk(
                        sessionId, recent.code(), recent.message(), clock.now()));
            } catch (Exception ignored) {
            }
        }
        return () -> set.remove(listener);
    }

    /** 快速失败错误的补偿快照（attachListener 补发用）。 */
    private record RecentError(long at, String code, String message) {
    }

    // sessionId -> 最近一次 send 快速失败的错误（3 秒窗口内对迟到的 SSE 订阅者补发）。
    private final Map<String, RecentError> recentErrors = new ConcurrentHashMap<>();
    // 复活互斥锁：同一会话并发首用（发消息 + 拉模型目录）只允许一次重建。
    private final Map<String, Object> resurrectLocks = new ConcurrentHashMap<>();

    @Override
    public int ensureEndpoint(String sessionId) {
        return ensureServe(sessionId);
    }

    /**
     * 懒复活：后端重启后 {@code sessionPorts} 内存映射为空，但 SQLite 会话行仍在。该会话
     * 首次被使用时按行内记录重新拉起 serve（同一 clonePath），把新端口写回会话行并重接上游
     * 事件流。opencode 会话数据在全局存储里，新 serve + 旧 cliSessionId 天然续接（等价于
     * CLI 的 {@code opencode -s <id>}）。MCP provisioning 用重新铸造的 agent token 重做；
     * credentials 未注入时退化为无 gate 工具的裸 serve（会话对话不受影响）。
     *
     * <p>状态不是门槛：ABORTED 行（历史启动清扫或硬中止）同样可复活——用户对旧会话再次
     * 发送即是明确的继续意图，opencode 侧数据完好。只缺 cliSessionId 的行无从续接，拒绝。
     */
    private int ensureServe(String sessionId) {
        Integer existing = sessionPorts.get(sessionId);
        if (existing != null) {
            return existing;
        }
        Object lock = resurrectLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            existing = sessionPorts.get(sessionId);
            if (existing != null) {
                return existing;
            }
            Session s = sessions.find(sessionId)
                    .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                            "no such session: " + sessionId));
            if (s.cliSessionId() == null || s.cliSessionId().isBlank()) {
                throw new GateException(GateErrorCode.USAGE,
                        "session has no cli session id to resume: " + sessionId);
            }
            int port = acquireUsablePort();
            try {
                if (opencodeExecutable != null && !opencodeExecutable.isBlank()) {
                    Map<String, String> env = credentials == null
                            ? Map.of()
                            : Map.of(gate.adapters.mcp.McpServer.TOKEN_ENV,
                                    credentials.issueAgentToken(s.ticketNo(), clock.now()));
                    spawnServe(port, s.clonePath(), env);
                }
                waitHealthy(port);
            } catch (Exception e) {
                killProcess(port);
                ports.release(port);
                throw e;
            }
            sessionPorts.put(sessionId, port);
            // 终态行复活即回到 ACTIVE（清 finished_at），后续软中止等路径恢复正常语义。
            Session resumed = s.status() == SessionStatus.ACTIVE
                    ? s.withAllocatedPort(port)
                    : s.withStatus(SessionStatus.ACTIVE).withFinishedAt(null).withAllocatedPort(port);
            sessions.update(resumed);
            // 复活先对账再接流：后端重启/硬中止后 sessionPorts 清空才走到这里，此时上游
            // （opencode 自有会话存储）里可能留着 gate 从未落库的回合；按会话时间截点补齐，
            // 再让 reader 以 lastEventId=null 接续新事件。失败只降级告警，绝不阻断复活。
            if (!upstreams.containsKey(sessionId)) {
                backfillFromServe(sessionId, port, s.cliSessionId());
            }
            ensureUpstream(sessionId, port, s.cliSessionId());
            log.info("opencode", "session.resurrected", "sessionId", sessionId,
                    "port", port, "cliSessionId", s.cliSessionId());
            return port;
        }
    }

    private void emitChunk(String sessionId, SessionStreamChunk chunk) {
        Set<Consumer<SessionStreamChunk>> set = listeners.get(sessionId);
        if (set != null) {
            for (Consumer<SessionStreamChunk> listener : set) {
                try {
                    listener.accept(chunk);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void respondPermission(String sessionId, String permissionId, String response) {
        postWithRecovery(sessionId, "/permission/" + permissionId + "/reply",
                "{\"reply\":\"" + response + "\"}", "permission reply", permissionId,
                resp -> {
                    if (resp.statusCode() == 404) {
                        // Already answered (e.g. the auto-allow won the race); treat as resolved.
                        log.info("opencode", "permission.already-resolved", "sessionId", sessionId,
                                "permissionId", permissionId);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                                "opencode permission reply failed: HTTP " + resp.statusCode() + " " + resp.body());
                    }
                });
        removePending(sessionId, permissionId);
    }

    /**
     * 带 serve 自愈的 opencode HTTP 应答：权限/提问卡片可能挂很久，期间 serve 进程死亡
     * （崩溃/被杀/端口失联）会让旧端口永久失效——此前直接抛 "opencode permission reply
     * failed"，卡片从此卡死只能重发消息。现改为 POST 前探活，失败时按 send 路径同一套
     * {@link #ensureServe} 懒复活（新端口、重接上游流）后重试一次；仍失败才抛错。
     */
    private void postWithRecovery(String sessionId, String path, String body, String what,
                                  String ref, java.util.function.Consumer<HttpResponse<String>> check) {
        Integer port = sessionPorts.get(sessionId);
        HttpResponse<String> resp = null;
        Exception postError = null;
        if (port != null) {
            try {
                resp = post("http://127.0.0.1:" + port + path, body, PERMISSION_HTTP_TIMEOUT);
            } catch (Exception e) {
                postError = e;
            }
        }
        if (resp == null) {
            // 端口缺失或 POST 失败：serve 大概率已死。失效端口先清理（否则 ensureServe
            // 会盲返回旧端口，重试等于没修），再按 send 路径同一套懒复活拉起新 serve
            // 后重试一次；复活本身失败则把原始错误上抛，语义与旧行为一致。
            try {
                if (port != null && !serveHealthy(port)) {
                    log.warn("opencode", "reply.serve-dead-heal", "sessionId", sessionId,
                            "port", port);
                    stopUpstream(sessionId);
                    killProcess(port);
                    ports.release(port);
                    sessionPorts.remove(sessionId);
                }
                int fresh = ensureServe(sessionId);
                resp = post("http://127.0.0.1:" + fresh + path, body, PERMISSION_HTTP_TIMEOUT);
            } catch (Exception e) {
                if (postError != null) {
                    throw new GateException(GateErrorCode.GATE_ERROR_IO,
                            "opencode " + what + " failed: " + ref
                                    + " (serve unreachable and recovery failed: "
                                    + e.getMessage() + ")", postError);
                }
                throw e instanceof GateException ge ? ge
                        : new GateException(GateErrorCode.GATE_ERROR_IO,
                                "opencode " + what + " failed: " + ref, e);
            }
        }
        check.accept(resp);
    }

    /** 端口快速探活（/health，500ms）：连接拒绝 = 进程已死，其余一律视为存活。 */
    private boolean serveHealthy(int port) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofMillis(500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<PermissionRequest> pendingPermissions(String sessionId) {
        Map<String, PermissionRequest> merged = new LinkedHashMap<>();
        Map<String, PermissionRequest> local = pendingPermissions.get(sessionId);
        if (local != null) {
            merged.putAll(local);
        }
        Integer port = sessionPorts.get(sessionId);
        Session session = sessions.find(sessionId).orElse(null);
        if (port != null && session != null && session.cliSessionId() != null) {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/permission"))
                        .timeout(PERMISSION_HTTP_TIMEOUT).GET().build();
                HttpResponse<String> resp = http.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 == 2) {
                    Object parsed = MiniJson.parse(resp.body().trim());
                    if (parsed instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof Map<?, ?> m)) {
                                continue;
                            }
                            Map<String, Object> obj = castMap(m);
                            // The /permission snapshot is instance-wide; keep this session's asks.
                            if (!session.cliSessionId().equals(str(obj.get("sessionID")))) {
                                continue;
                            }
                            PermissionRequest r = permissionFromProps(obj);
                            if (r.permissionId() != null) {
                                merged.put(r.permissionId(), r);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Serve unreachable (restarting / down): fall back to the in-memory table only.
                log.warn("opencode", "permission.list-failed", "sessionId", sessionId,
                        "error", e.getClass().getSimpleName());
            }
        }
        return List.copyOf(merged.values());
    }

    private void removePending(String sessionId, String permissionId) {
        Map<String, PermissionRequest> local = pendingPermissions.get(sessionId);
        if (local != null) {
            local.remove(permissionId);
        }
    }

    @Override
    public List<QuestionRequest> pendingQuestions(String sessionId) {
        Map<String, QuestionRequest> merged = new LinkedHashMap<>();
        Map<String, QuestionRequest> local = pendingQuestions.get(sessionId);
        if (local != null) {
            merged.putAll(local);
        }
        Integer port = sessionPorts.get(sessionId);
        Session session = sessions.find(sessionId).orElse(null);
        if (port != null && session != null && session.cliSessionId() != null) {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/question"))
                        .timeout(PERMISSION_HTTP_TIMEOUT).GET().build();
                HttpResponse<String> resp = http.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 == 2) {
                    Object parsed = MiniJson.parse(resp.body().trim());
                    if (parsed instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof Map<?, ?> m)) {
                                continue;
                            }
                            Map<String, Object> obj = castMap(m);
                            // The /question snapshot is instance-wide; keep this session's asks.
                            if (!session.cliSessionId().equals(str(obj.get("sessionID")))) {
                                continue;
                            }
                            QuestionRequest r = questionFromProps(obj);
                            if (r.requestId() != null) {
                                merged.put(r.requestId(), r);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Serve unreachable (restarting / down): fall back to the in-memory table only.
                log.warn("opencode", "question.list-failed", "sessionId", sessionId,
                        "error", e.getClass().getSimpleName());
            }
        }
        return List.copyOf(merged.values());
    }

    @Override
    public void respondQuestion(String sessionId, String requestId, List<List<String>> answers) {
        List<String> encoded = new ArrayList<>();
        for (List<String> picked : answers == null ? List.<List<String>>of() : answers) {
            encoded.add(jsonValue(picked == null ? List.of() : picked));
        }
        String body = "{\"answers\":[" + String.join(",", encoded) + "]}";
        postWithRecovery(sessionId, "/question/" + requestId + "/reply", body, "question reply",
                requestId, resp -> {
                    if (resp.statusCode() == 404) {
                        // Already answered/rejected elsewhere; treat as resolved.
                        log.info("opencode", "question.already-resolved", "sessionId", sessionId,
                                "requestId", requestId);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                                "opencode question reply failed: HTTP " + resp.statusCode() + " " + resp.body());
                    }
                });
        removePendingQuestion(sessionId, requestId);
    }

    @Override
    public void rejectQuestion(String sessionId, String requestId) {
        postWithRecovery(sessionId, "/question/" + requestId + "/reject", "{}", "question reject",
                requestId, resp -> {
                    if (resp.statusCode() != 404 && resp.statusCode() / 100 != 2) {
                        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                                "opencode question reject failed: HTTP " + resp.statusCode() + " " + resp.body());
                    }
                });
        removePendingQuestion(sessionId, requestId);
    }

    private void removePendingQuestion(String sessionId, String requestId) {
        Map<String, QuestionRequest> local = pendingQuestions.get(sessionId);
        if (local != null) {
            local.remove(requestId);
        }
    }

    /** Best-effort reject of all in-memory pending asks so a new turn cannot be blocked by one. */
    private void rejectPendingPermissions(String sessionId, int port) {
        Map<String, PermissionRequest> local = pendingPermissions.get(sessionId);
        if (local == null || local.isEmpty()) {
            return;
        }
        for (String permissionId : List.copyOf(local.keySet())) {
            try {
                post("http://127.0.0.1:" + port + "/permission/" + permissionId + "/reply",
                        "{\"reply\":\"reject\"}", PERMISSION_HTTP_TIMEOUT);
                removePending(sessionId, permissionId);
            } catch (Exception e) {
                // 尽力而为：一个失败的 reject 不应中断新回合的触发。
                log.warn("opencode", "permission.reject-failed", "sessionId", sessionId,
                        "permissionId", permissionId, "error", e.getClass().getSimpleName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            typed.put(String.valueOf(e.getKey()), (Object) e.getValue());
        }
        return typed;
    }

    @SuppressWarnings("unchecked")
    private static PermissionRequest permissionFromProps(Map<String, Object> props) {
        Map<String, Object> tool = props.get("tool") instanceof Map<?, ?> t
                ? (Map<String, Object>) t : Map.of();
        return new PermissionRequest(
                str(props.get("id")),
                str(props.get("permission")),
                stringList(props.get("patterns")),
                stringList(props.get("always")),
                props.get("metadata") instanceof Map<?, ?> meta ? (Map<String, Object>) meta : Map.of(),
                str(tool.get("messageID")),
                str(tool.get("callID")));
    }

    private static List<String> stringList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * Maps a {@code question.asked} property map (or a {@code /question} snapshot entry) onto
     * {@link QuestionRequest}: {id, sessionID, questions:[{question, header, options, multiple?,
     * custom?}], tool?{messageID, callID}}.
     */
    @SuppressWarnings("unchecked")
    private static QuestionRequest questionFromProps(Map<String, Object> props) {
        Map<String, Object> tool = props.get("tool") instanceof Map<?, ?> t
                ? (Map<String, Object>) t : Map.of();
        List<QuestionRequest.QuestionPrompt> prompts = new ArrayList<>();
        if (props.get("questions") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> q)) {
                    continue;
                }
                Map<String, Object> qm = castMap(q);
                List<QuestionRequest.QuestionOption> options = new ArrayList<>();
                if (qm.get("options") instanceof List<?> opts) {
                    for (Object o : opts) {
                        if (!(o instanceof Map<?, ?> om)) {
                            continue;
                        }
                        Map<String, Object> opt = castMap(om);
                        options.add(new QuestionRequest.QuestionOption(
                                str(opt.get("label")), str(opt.get("description"))));
                    }
                }
                // opencode defaults custom (free-text answer) to true; only an explicit false disables it.
                prompts.add(new QuestionRequest.QuestionPrompt(
                        str(qm.get("question")),
                        str(qm.get("header")),
                        options,
                        Boolean.TRUE.equals(qm.get("multiple")),
                        !Boolean.FALSE.equals(qm.get("custom"))));
            }
        }
        return new QuestionRequest(
                str(props.get("id")),
                prompts,
                str(tool.get("messageID")),
                str(tool.get("callID")));
    }

    /** question.replied answers payload: {@code [[label,…],…]} → typed lists. */
    private static List<List<String>> answerLists(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<List<String>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof List<?> picked)) {
                continue;
            }
            List<String> labels = new ArrayList<>();
            for (Object label : picked) {
                if (label != null) {
                    labels.add(String.valueOf(label));
                }
            }
            out.add(labels);
        }
        return out;
    }

    /**
     * T-118: re-sync the clone base before the agent touches the clone. Best-effort by design: a
     * failed or skipped sync must not block the session — a stale base surfaces loudly enough at
     * presubmit (BASE_STALE); the manual sync endpoint can replay a dirty worktree, which the
     * auto path (allowDirty=false) deliberately refuses to touch.
     */
    /** V19: true when the ticket is the project's quick-mode super ticket (clone_path = workspace). */
    private boolean isSuperTicket(String ticketNo) {
        return tickets.find(ticketNo).map(gate.domain.ticket.Ticket::isSuper).orElse(false);
    }

    private void syncBaseQuietly(String ticketNo) {
        if (baseSynchronizer == null) {
            return;
        }
        try {
            baseSynchronizer.syncBase(ticketNo, false);
        } catch (Exception e) {
            log.info("opencode", "basesync.skipped-exception", "ticketNo", ticketNo,
                    "error", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public Session start(StartRequest request) {
        try (AutoCloseable ignored = ticketLocks.acquire(request.ticketNo())) {
            // V19 快速模式: the super ticket's cwd IS the project workspace — never git-touch it
            // here (no fetch/rebase on the user's real repo); the workspace is already on its base.
            if (!isSuperTicket(request.ticketNo())) {
                syncBaseQuietly(request.ticketNo());
            }
            AgentConfig config = agentConfigs.find(request.agentConfigId())
                    .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                            "no such agent config: " + request.agentConfigId()));
            int port = acquireUsablePort();
            try {
                if (opencodeExecutable != null && !opencodeExecutable.isBlank()) {
                    // A leftover opencode serve from a previous backend run silently answers /health
                    // on this port with STALE in-memory config; our own spawned process loses the
                    // bind race and dies while waitHealthy talks to the orphan instead.
                    // acquireUsablePort 已确保端口干净（孤儿自愈 + 2^n 跨步），这里直接拉起。
                    spawnServe(port, request.clonePath(), request.env());
                }
                waitHealthy(port);
                String cliSessionId = createSession(port);
                Session session = new Session(UUID.randomUUID().toString(), request.ticketNo(),
                        config.id(), AgentCli.OPENCODE, SessionStatus.ACTIVE, cliSessionId,
                        request.clonePath(), port, clock.now(), null, SessionUsage.EMPTY,
                        null, false);
                sessions.insert(session);
                sessionPorts.put(session.id(), port);
                ensureUpstream(session.id(), port, cliSessionId);
                log.info("opencode", "session.started",
                        "sessionId", session.id(), "ticketNo", request.ticketNo(),
                        "port", port, "cliSessionId", cliSessionId);
                return session;
            } catch (Exception e) {
                // Never leak a half-started serve process: its port would stay occupied on disk even
                // though the allocator's reservation is released, and every failed retry would spawn
                // another orphan (设计 §R1 — the 10s wait used to fail on cold boot + leak each try).
                killProcess(port);
                ports.release(port);
                throw e;
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "opencode start failed", e);
        }
    }

    @Override
    public String sendMessage(SendRequest request) {
        Session session = sessions.find(request.sessionId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + request.sessionId()));
        // First-turn detection must happen BEFORE the user message is persisted: 注入上下文
        // (项目/工单信息) rides along only on the session's opening turn.
        boolean firstTurn = sessions.findMessages(session.id()).isEmpty();
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                Role.USER, request.message(), List.of(), null, false, clock.now()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        // 入队即算运行，且 busy 持续到回合真正结束（opencode 是异步回合：终点是上游
        // session.status=idle，不是 prompt_async 的 HTTP 受理）。失败路径在 catch 里兜底释放。
        incrementInFlight(session.id());
        executor.submit(() -> runSend(task, session, request.message(), request.attachments(), firstTurn));
        return task.id();
    }

    @Override
    public void abort(String sessionId) {
        // 兜底清除 busy：abort 即视为回合终止，应立即移出 busy 集合，避免 runSend 仍在阻塞时顶栏持续显示；完全移除以覆盖同一 session 重复 send 的计数
        inFlightCounts.remove(sessionId);
        acceptedSinceRelease.remove(sessionId);
        sessions.find(sessionId).ifPresent(s -> {
            Integer port = sessionPorts.get(sessionId);
            // Soft abort first: stop the in-flight turn but keep the serve and the session
            // ACTIVE so the user can immediately continue the same session. The interrupted
            // turn is flushed right away (degraded) so a history reload still shows the
            // half-streamed reply, and the done chunk unblocks the browser stream — without
            // it the UI spinner would sit on its 300s fallback.
            if (s.status() != SessionStatus.ABORTED && port != null && s.cliSessionId() != null
                    && postQuietly("http://127.0.0.1:" + port + "/session/" + s.cliSessionId() + "/abort",
                            "{}", Duration.ofSeconds(2))) {
                Upstream up = upstreams.get(sessionId);
                if (up != null) {
                    up.flushTurn("aborted");
                }
                emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, s.cliSessionId(), clock.now()));
                return;
            }
            hardAbort(sessionId, s, port);
        });
    }

    /**
     * Legacy teardown: persist the interrupted turn, kill the serve, mark the session ABORTED.
     * Used when opencode does not acknowledge the soft abort, and by delete/archive (the route
     * pre-marks the session ABORTED so this path is taken).
     */
    private void hardAbort(String sessionId, Session s, Integer port) {
        Upstream up = stopUpstream(sessionId);
        if (up != null) {
            // Persist whatever the interrupted turn buffered BEFORE tearing the serve
            // process down, so a session switch after the abort does not lose the
            // half-streamed reply (degraded, recover-only).
            up.flushTurn("aborted");
        }
        if (port != null && s.cliSessionId() != null) {
            postQuietly("http://127.0.0.1:" + port + "/session/" + s.cliSessionId() + "/abort", "{}",
                    Duration.ofSeconds(2));
        }
        killProcess(port);
        if (port != null) {
            ports.release(port);
            sessionPorts.remove(sessionId);
        }
        sessions.update(s.withStatus(SessionStatus.ABORTED).withFinishedAt(clock.now()));
        pendingPermissions.remove(sessionId);
        pendingQuestions.remove(sessionId);
        // stopUpstream already detached the reader, so no natural done/error will ever reach
        // the browser — emit one ourselves or the UI keeps its stop button until timeout.
        emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, s.cliSessionId(), clock.now()));
    }

    @Override
    public List<SessionMessage> getHistory(String sessionId) {
        return sessions.findMessages(sessionId);
    }

    @Override
    public Set<String> busySessionIds() {
        // 排序后的不可变快照，输出稳定便于测试
        List<String> sorted = new ArrayList<>(inFlightCounts.keySet());
        Collections.sort(sorted);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private void incrementInFlight(String sessionId) {
        inFlightCounts.compute(sessionId, (k, v) -> {
            if (v == null) return new AtomicInteger(1);
            v.incrementAndGet();
            return v;
        });
    }

    private void decrementInFlight(String sessionId) {
        inFlightCounts.computeIfPresent(sessionId, (k, v) -> v.decrementAndGet() <= 0 ? null : v);
    }

    /**
     * 回合终点（idle/error）到达时消耗一次受理计数。只有“已受理”的回合才能被终点释放：
     * 连接快照、重连重放等陈旧终点帧没有受理记录，直接忽略，不会误清新回合的 busy。
     * 引用计数归零（entry 移除）时才关闭受理状态，重复 send 排队时后续终点仍可逐次消耗。
     */
    private void releaseBusyOnTurnEnd(String sessionId) {
        if (!acceptedSinceRelease.contains(sessionId)) {
            return;
        }
        decrementInFlight(sessionId);
        if (!inFlightCounts.containsKey(sessionId)) {
            acceptedSinceRelease.remove(sessionId);
        }
    }

    /** 无条件兜底释放（prompt_async 被拒等永远不会有终点的路径），并同步关闭受理状态。 */
    private void releaseBusyUnconditionally(String sessionId) {
        decrementInFlight(sessionId);
        if (!inFlightCounts.containsKey(sessionId)) {
            acceptedSinceRelease.remove(sessionId);
        }
    }

    @Override
    public Stream<SessionEvent> streamEvents(String sessionId) {
        List<SessionEvent> events = new ArrayList<>();
        for (SessionMessage m : sessions.findMessages(sessionId)) {
            events.add(new SessionEvent(sessionId, m, "message"));
        }
        return events.stream();
    }

    public void close() {
        // 停机前先把仍在内存的回合缓冲落库（T-113 记录丢失的根因：后端重启时 close() 只
        // stop 了 reader 线程，整个未 idle 回合的文本/工具随进程丢弃，而 opencode 侧完好）。
        for (Upstream up : upstreams.values()) {
            up.flushTurn("shutdown", true);
            up.stop();
        }
        upstreams.clear();
        watchdog.shutdownNow();
        for (Integer port : List.copyOf(sessionPorts.values())) {
            killProcess(port);
            ports.release(port);
        }
        sessionPorts.clear();
        pendingPermissions.clear();
        pendingQuestions.clear();
        recentErrors.clear();
        executor.shutdown();
    }

    // -------------------------------------------------------------------------------------------
    // Send path: fire prompt_async, streaming happens on the upstream event reader
    // -------------------------------------------------------------------------------------------

    private void runSend(GateTask task, Session session, String message,
                         List<AgentSessionPort.Attachment> attachments, boolean firstTurn) {
        try (AutoCloseable ignored = ticketLocks.acquire(session.ticketNo())) {
            // 后端重启后 sessionPorts 为空：懒复活按会话行重建 serve（同一 clonePath、新端口、
            // 重接上游事件流），旧 cliSessionId 由 opencode 全局存储续接。复活失败抛错走下方
            // 统一失败路径（ERROR 落库 + attachListener 补发）。
            int port = ensureServe(session.id());
            if (session.cliSessionId() == null) {
                throw new GateException(GateErrorCode.USAGE, "session has no opencode endpoint");
            }
            // Fresh read: a model/variant switch persisted after this task was enqueued must
            // still win (会话内实时切换 semantics — the next send uses the latest selection).
            Session latest = sessions.find(session.id()).orElse(session);
            AgentConfig config = agentConfigs.find(latest.agentConfigId()).orElseThrow();
            tasks.update(progress(task, 20, "触发 opencode 回合"));
            // A permission.asked left unanswered would block the new turn forever (opencode waits
            // on it before continuing); reject every residual pending ask best-effort.
            rejectPendingPermissions(session.id(), port);
            // OpenCode 的 prompt_async 没有独立 system 通道：注入上下文（项目/工单信息，
            // 以及 AgentConfig.systemPrompt）只在会话首个回合作为带分隔线的前缀随行。
            String outgoing = message;
            if (firstTurn) {
                Ticket ctxTicket = tickets.find(latest.ticketNo()).orElse(null);
                Project project = null;
                if (projects != null && ctxTicket != null && ctxTicket.projectId() != null) {
                    project = projects.find(ctxTicket.projectId()).orElse(null);
                }
                TicketStageChangeRepository.StageChangeRow latestRevive =
                        restarts == null ? null : restarts.latestRevive(latest.ticketNo()).orElse(null);
                String context = AgentContextPrompt.compose(config, latest.ticketNo(),
                        ctxTicket == null ? null : ctxTicket.targetRef(), ctxTicket, project, latestRevive);
                if (!context.isBlank()) {
                    outgoing = context + "\n\n---\n\n" + message;
                }
            }
            String body = messageBody(config, outgoing, attachments,
                    latest.overrideProvider(), latest.overrideModel(), latest.overrideVariant());
            Upstream up = upstreams.get(session.id());
            if (up != null) {
                // A previous turn whose stream never reached session.status=idle (serve drop,
                // connection loss, interrupted abort) left buffered content dangling; the reset
                // below would wipe it, so flush it as a degraded reply first and let it survive
                // the next send.
                up.flushTurn("superseded");
                // Reset BEFORE firing the request: once prompt_async lands, events for this turn
                // can arrive within milliseconds and must not be wiped by post-send cleanup.
                // 与 reader 线程的 step 合并并发，统一经 turnLock 串行化。
                synchronized (up.turnLock) {
                    up.assistantPersistedSinceSend = false;
                    up.pendingErrorName = null;
                    up.pendingErrorMessage = null;
                    up.turnText.setLength(0);
                    synchronized (up.turnTools) {
                        up.turnTools.clear();
                    }
                    up.turnParts.clear();
                    up.turnUsage = null;
                    up.turnHasNewContent = false;
                }
            }
            HttpResponse<String> resp = post("http://127.0.0.1:" + port + "/session/"
                    + session.cliSessionId() + "/prompt_async", body);
            if (resp.statusCode() / 100 != 2) {
                log.error("opencode", "prompt_async.rejected",
                        "sessionId", session.id(), "status", resp.statusCode(),
                        "bodySnippet", resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "opencode prompt_async failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            log.info("opencode", "prompt_async.accepted", "sessionId", session.id(),
                    "cliSessionId", session.cliSessionId(), "chars", message.length());
            // 受理即进入“等待终点释放”状态：busy 只能被本回合之后的 idle/error 终点消耗一次。
            acceptedSinceRelease.add(session.id());
            // The turn itself runs asynchronously; token/tool/done chunks arrive on the upstream
            // reader and the final assistant message is persisted from its completion snapshot.
            tasks.update(success(task, "{\"accepted\":true}"));
        } catch (Throwable e) {
            // Persist the failure so a page reload still shows why the turn died, mirroring the
            // claude adapter's ERROR-message behaviour.
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                    Role.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    List.of(), null, true, clock.now()));
            emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "INTERNAL_ERROR", e.getMessage(), clock.now()));
            // 浏览器的 SSE 多半还没挂上（POST /messages 刚返回）：记入 3 秒补偿窗口，
            // attachListener 迟到即补发，否则 UI 转圈到看门狗超时。
            recentErrors.put(session.id(), new RecentError(System.currentTimeMillis(),
                    "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            // 回合根本没被受理：不会有 idle 事件到来，立即释放 busy 计数。必须先于任务簿记——
            // 簿记失败（fence 冲突等）绝不允许把 busy 泄漏成常驻 1。
            releaseBusyUnconditionally(session.id());
            try {
                tasks.update(fail(task, e));
            } catch (Exception taskEx) {
                log.warn("opencode", "send.fail-task-update", "sessionId", session.id(),
                        "error", taskEx.getClass().getSimpleName());
            }
        }
        // 正常路径不在此清除 busy：prompt_async 只表示“已受理”，回合本身异步运行——
        // busy 生命周期到上游读取线程收到 session.status=idle 为止（handleSessionStatus）。
    }

    static String messageBody(AgentConfig config, String message) {
        return messageBody(config, message, null, null, null);
    }

    /**
     * Builds the {@code prompt_async} body. The live per-session override (provider/model/variant,
     * 会话内实时切换) wins over the AgentConfig defaults; a blank override falls back to
     * {@code config.model()} parsed as {@code provider/model}. {@code variant} is OpenCode's
     * reasoning-effort selection (same field the OpenChamber composer sends).
     */
    static String messageBody(AgentConfig config, String message,
                              String overrideProvider, String overrideModel, String overrideVariant) {
        return messageBody(config, message, List.of(), overrideProvider, overrideModel, overrideVariant);
    }

    /**
     * Full shape: image attachments ride along as {@code file} parts after the text part — the
     * same contract the OpenChamber composer uses ({@code {type:"file", mime, filename?, url}} with
     * a {@code data:} URL payload).
     */
    static String messageBody(AgentConfig config, String message,
                              List<AgentSessionPort.Attachment> attachments,
                              String overrideProvider, String overrideModel, String overrideVariant) {
        StringBuilder body = new StringBuilder("{\"parts\":[{\"type\":\"text\",\"text\":\"")
                .append(escapeJson(message)).append("\"}");
        for (AgentSessionPort.Attachment attachment : attachments == null ? List.<AgentSessionPort.Attachment>of() : attachments) {
            body.append(",{\"type\":\"file\",\"mime\":\"").append(escapeJson(attachment.mime()))
                    .append("\",\"filename\":\"").append(escapeJson(attachment.filename() == null ? "image" : attachment.filename()))
                    .append("\",\"url\":\"data:").append(escapeJson(attachment.mime()))
                    .append(";base64,").append(escapeJson(attachment.dataBase64()))
                    .append("\"}");
        }
        body.append(']');
        ModelRef model = resolveModel(config, overrideProvider, overrideModel);
        if (model != null) {
            body.append(",\"model\":{\"providerID\":\"")
                    .append(escapeJson(model.providerId()))
                    .append("\",\"modelID\":\"")
                    .append(escapeJson(model.modelId()))
                    .append("\"}");
        }
        if (overrideVariant != null && !overrideVariant.isBlank()) {
            body.append(",\"variant\":\"").append(escapeJson(overrideVariant.trim())).append('"');
        }
        String agent = agentFlag(config);
        if (agent != null && !agent.isBlank()) {
            // Explicit agent beats opencode's server-side default_agent; without this the send
            // 500s when the configured default agent no longer exists ("default agent X not found").
            body.append(",\"agent\":\"").append(escapeJson(agent)).append("\"");
        }
        return body.append('}').toString();
    }

    /** Override pair when both halves are present, else the AgentConfig default, else none. */
    private static ModelRef resolveModel(AgentConfig config, String provider, String model) {
        if (provider != null && !provider.isBlank() && model != null && !model.isBlank()) {
            return new ModelRef(provider.trim(), model.trim());
        }
        return ModelRef.parse(config.model());
    }

    /**
     * Optional agent override via an {@code --agent=<name>} / {@code agent=<name>} entry in the
     * AgentConfig extra flags (unlike the claude adapter, these flags are not CLI argv here).
     */
    static String agentFlag(AgentConfig config) {
        if (config.extraFlags() == null) {
            return null;
        }
        for (String flag : config.extraFlags()) {
            if (flag == null) {
                continue;
            }
            String f = flag.trim();
            if (f.startsWith("--agent=")) {
                return f.substring("--agent=".length()).trim();
            }
            if (f.startsWith("agent=")) {
                return f.substring("agent=".length()).trim();
            }
        }
        return null;
    }

    private record ModelRef(String providerId, String modelId) {
        private static ModelRef parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            int slash = value.indexOf('/');
            if (slash <= 0 || slash >= value.length() - 1) {
                return null;
            }
            return new ModelRef(value.substring(0, slash), value.substring(slash + 1));
        }
    }

    private void writeback(Session session) {
        try {
            if (session.cumulativeUsage() != null && session.cumulativeUsage().totalTokens() != null) {
                tickets.updateExecTokens(session.ticketNo(), session.cumulativeUsage().totalTokens(),
                        "agent_cli", clock.now());
            }
        } catch (Exception ignored) {
            // bypass-only
        }
    }

    // -------------------------------------------------------------------------------------------
    // Upstream SSE reader: one persistent /event subscription per gate session
    // -------------------------------------------------------------------------------------------

    /** Per-session upstream state: reader thread, cursor, and streaming accumulators. */
    private final class Upstream implements Runnable {
        final String sessionId;
        final int port;
        final String cliSessionId;
        final Thread thread;
        final Map<String, Integer> partSeen = new ConcurrentHashMap<>();
        // Final snapshot text per assistant message: messageId -> (partId -> latest full text).
        // Replace-not-append keeps persistence idempotent: opencode re-announces a finished
        // step's text under a fresh part id, and the former append model doubled exactly that
        // content in persisted history (visible after switching sessions).
        final Map<String, LinkedHashMap<String, String>> messageParts = new ConcurrentHashMap<>();
        // Tool calls per assistant message keyed by upstream callID; upserted as state
        // transitions stream in and drained into the persisted ASSISTANT row on completion,
        // so reloading a session re-renders 工具调用 instead of degrading to plain text.
        final Map<String, LinkedHashMap<String, ToolCallState>> toolsByMessage = new ConcurrentHashMap<>();
        // ZCode 式时间线草稿：messageId -> (槽键 -> 槽)。槽键前缀定序（t: 文本 / r: 思考 /
        // 工具 callID），LinkedHashMap 保到达序；text 是快照 replace、thinking 增量 append、
        // tool 原位更新终态。步完成（mergeStepIntoTurn）按序 drain 成 TurnPart 进 turnParts。
        final Map<String, LinkedHashMap<String, PartSlot>> partsByMessage = new ConcurrentHashMap<>();
        final java.util.Set<String> mergedMessages = ConcurrentHashMap.newKeySet();
        // Roles are announced via message.updated before a message's parts stream in; user-message
        // parts echo the prompt verbatim and must never surface as assistant content chunks.
        final java.util.Set<String> assistantMessages = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> userMessages = ConcurrentHashMap.newKeySet();
        // Turn grouping (openchamber-style): an agentic turn spans MANY assistant messages
        // (one per step). Everything accumulates here and persists as ONE reply when the
        // turn goes idle — instead of one noisy bubble per intermediate CoT step.
        final StringBuilder turnText = new StringBuilder();
        final List<TurnTool> turnTools = java.util.Collections.synchronizedList(new ArrayList<>());
        // ZCode 式时间线分段：reasoning/text/tool 按真实到达序累积，落库后历史可整段
        // 重放（思考折叠块 + 文本段 + 工具行交错），不再是"思考一坨、工具一堆、文本垫底"。
        // 读写者与 turnText 相同（reader 合并 / superseded 重置 / flush 落库），一律持 turnLock。
        final List<TurnPart> turnParts = new ArrayList<>();
        volatile SessionUsage turnUsage;
        volatile boolean turnHasNewContent;
        volatile boolean stopped;
        volatile String lastEventId;
        volatile long lastEventAt = System.currentTimeMillis();
        volatile long lastDoneAt;
        volatile InputStream currentBody;
        // Turn bookkeeping: if a turn ends (idle) without any assistant completion, the buffered
        // session.error is persisted as an ERROR row so failures survive page reloads.
        volatile boolean assistantPersistedSinceSend;
        volatile String pendingErrorName;
        volatile String pendingErrorMessage;
        // 序列化回合缓冲的全部读写者：reader 线程（step 合并）、send 线程（superseded 重置）、
        // 停机/错误路径（flush 落库）。turnText 是普通 StringBuilder，跨线程读写必须加锁。
        // flush 的 DB I/O 在锁外执行，锁只覆盖缓冲快照与清空。
        final Object turnLock = new Object();

        Upstream(String sessionId, int port, String cliSessionId) {
            this.sessionId = sessionId;
            this.port = port;
            this.cliSessionId = cliSessionId;
            this.thread = new Thread(this, "opencode-events-" + sessionId.substring(0, 8));
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            closeBody();
            thread.interrupt();
        }

        /** One journaled tool call within the current turn; updated in place as state streams. */
        private record TurnTool(String callId, String name, String inputJson, String output, String status) {
        }

        private void upsertTurnTool(String callId, String name, String inputJson, String output, String status) {
            synchronized (turnTools) {
                for (int i = 0; i < turnTools.size(); i++) {
                    if (turnTools.get(i).callId().equals(callId)) {
                        turnTools.set(i, new TurnTool(callId, name, inputJson, output, status));
                        return;
                    }
                }
                turnTools.add(new TurnTool(callId, name, inputJson, output, status));
            }
        }

        void closeBody() {
            InputStream body = currentBody;
            currentBody = null;
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                }
            }
        }

        boolean stale() {
            return !stopped && System.currentTimeMillis() - lastEventAt > UPSTREAM_STALL_TIMEOUT_MS;
        }

        @Override
        public void run() {
            while (!stopped) {
                try {
                    HttpRequest.Builder req = HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/event"))
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofSeconds(30))
                            .GET();
                    if (lastEventId != null && !lastEventId.isBlank()) {
                        // Resume where we left off; opencode replays everything after this id.
                        req.header("Last-Event-ID", lastEventId);
                    }
                    HttpResponse<InputStream> resp =
                            http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                    lastEventAt = System.currentTimeMillis();
                    currentBody = resp.body();
                    log.info("opencode", "upstream.connected", "sessionId", sessionId,
                            "port", port, "lastEventId", lastEventId);
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!stopped && (line = reader.readLine()) != null) {
                            lastEventAt = System.currentTimeMillis();
                            if (line.startsWith("data:")) {
                                handleEventData(line.substring(5).trim());
                            } else if (line.startsWith("id:")) {
                                lastEventId = line.substring(3).trim();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log.warn("opencode", "upstream.dropped", "sessionId", sessionId,
                            "port", port, "error", e.getClass().getSimpleName());
                } finally {
                    currentBody = null;
                }
                if (stopped) {
                    return;
                }
                try {
                    Thread.sleep(UPSTREAM_RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** Dispatch one SSE data frame: {"id":"evt_..","type":"..","properties":{..}}. */
        @SuppressWarnings("unchecked")
        void handleEventData(String json) {
            if (json == null || json.isBlank()) {
                return;
            }
            Object parsed;
            try {
                parsed = MiniJson.parse(json);
            } catch (Exception ignored) {
                return;
            }
            if (!(parsed instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> obj = (Map<String, Object>) raw;
            Object id = obj.get("id");
            if (id != null) {
                lastEventId = String.valueOf(id);
            }
            String type = String.valueOf(obj.get("type"));
            Map<String, Object> props = obj.get("properties") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();

            switch (type) {
                case "message.part.updated" -> handlePartUpdated(props);
                case "message.updated" -> handleMessageUpdated(props);
                case "session.status" -> handleSessionStatus(props);
                case "session.error" -> handleSessionError(props);
                case "session.updated" -> handleSessionUpdated(props);
                case "permission.asked" -> handlePermissionAsked(props);
                case "permission.replied" -> handlePermissionReplied(props);
                case "question.asked" -> handleQuestionAsked(props);
                case "question.replied" -> handleQuestionResolved(props, false);
                case "question.rejected" -> handleQuestionResolved(props, true);
                default -> {
                    // server.connected, file.watcher.*, pty.*, ... are irrelevant here
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void handlePartUpdated(Map<String, Object> props) {
            Map<String, Object> part = props.get("part") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : null;
            if (part == null || !cliSessionId.equals(str(part.get("sessionID")))) {
                return;
            }
            Instant now = clock.now();
            String partType = str(part.get("type"));
            String messageId = str(part.get("messageID"));
            String partId = str(part.get("id"));

            if (messageId != null && userMessages.contains(messageId)) {
                return;
            }

            if ("text".equals(partType)) {
                String full = str(part.get("text"));
                if (full == null) {
                    return;
                }
                int prev = partSeen.getOrDefault(partId, 0);
                String suffix = full.length() > prev ? full.substring(prev) : "";
                Object explicitDelta = props.get("delta");
                String chunk = explicitDelta instanceof String s && !s.isEmpty() ? s : suffix;
                if (!chunk.isEmpty() || !full.isEmpty()) {
                    partSeen.put(partId, full.length());
                    // Snapshot text is keyed by its own messageId and only judged at that
                    // message's completion, so buffering before the role announcement is safe;
                    // dropping it here produced empty rows for fast steps (parts raced ahead of
                    // message.updated). Known user messages are excluded above.
                    if (messageId != null) {
                        messageParts.computeIfAbsent(messageId, k -> new LinkedHashMap<>())
                                .put(partId, full);
                        // 时间线草稿与快照同形（replace-not-append）：final 文本按 partId 原位更新。
                        draft(messageId, "t:" + partId, "text").text = new StringBuilder(full);
                    }
                    if (!chunk.isEmpty()) {
                        emitChunk(sessionId, new SessionStreamChunk.ContentChunk(sessionId, chunk, now));
                    }
                }
            } else if ("reasoning".equals(partType)) {
                String full = str(part.get("text"));
                if (full == null) {
                    return;
                }
                int prev = partSeen.getOrDefault(partId, 0);
                String suffix = full.length() > prev ? full.substring(prev) : "";
                Object explicitDelta = props.get("delta");
                String chunk = explicitDelta instanceof String s && !s.isEmpty() ? s : suffix;
                if (!chunk.isEmpty()) {
                    partSeen.put(partId, full.length());
                    emitChunk(sessionId, new SessionStreamChunk.ThinkingChunk(sessionId, chunk, now));
                    // 思考正文此前只进 SSE、不落库，历史里整块蒸发；现在进 per-message
                    // 有序草稿，随步合并进时间线（与 text/tool 同容器按到达序交错）。
                    if (messageId != null) {
                        draft(messageId, "r:" + partId, "thinking").text.append(chunk);
                    }
                }
            } else if ("tool".equals(partType)) {
                String callId = str(part.get("callID"));
                String toolName = str(part.get("tool"));
                Map<String, Object> state = part.get("state") instanceof Map<?, ?> st
                        ? (Map<String, Object>) st : Map.of();
                String status = switch (str(state.get("status"))) {
                    case "completed" -> "SUCCESS";
                    case "error" -> "FAILED";
                    default -> "RUNNING";
                };
                String output = str(state.get("output"));
                String inputJson = jsonValue(state.get("input"));
                emitChunk(sessionId, new SessionStreamChunk.ToolCallChunk(sessionId,
                        callId == null ? partId : callId,
                        toolName == null ? "unknown" : toolName,
                        inputJson,
                        output, status, now));
                // Journal the call so the persisted turn reply keeps tool cards after reload.
                upsertTurnTool(callId == null ? partId : callId,
                        toolName == null ? "unknown" : toolName, inputJson, output, status);
                // 时间线草稿：同一 callID 原位更新（state 快照反复重发），首次到达决定顺序。
                // messageId 缺失时跳过草稿（平铺 toolCalls 视图仍经 upsertTurnTool 保留）。
                String draftKey = callId == null ? partId : callId;
                if (draftKey != null && messageId != null) {
                    PartSlot slot = toolDraft(messageId, draftKey);
                    if (toolName != null && !toolName.isBlank()) {
                        slot.name = toolName;
                    }
                    if (inputJson != null) {
                        slot.inputJson = inputJson;
                    }
                    if (output != null) {
                        slot.output = output;
                    }
                }
                turnHasNewContent = true;
            } else if ("step-finish".equals(partType)) {                SessionUsage usage = usageFromTokens(part.get("tokens"));
                if (usage != null) {
                    emitChunk(sessionId, new SessionStreamChunk.UsageChunk(sessionId, usage, now));
                }
            }
            // step-start / snapshot / patch parts carry nothing the chat view needs today.
        }

        @SuppressWarnings("unchecked")
        private void handleMessageUpdated(Map<String, Object> props) {
            Map<String, Object> info = props.get("info") instanceof Map<?, ?> i
                    ? (Map<String, Object>) i : null;
            if (info == null || !cliSessionId.equals(str(info.get("sessionID")))) {
                return;
            }
            String messageId = str(info.get("id"));
            String role = str(info.get("role"));
            if ("assistant".equals(role)) {
                if (messageId != null) {
                    assistantMessages.add(messageId);
                }
            } else if ("user".equals(role)) {
                if (messageId != null) {
                    userMessages.add(messageId);
                    // A user part that raced ahead of this announcement buffered itself; drop it.
                    messageParts.remove(messageId);
                    toolsByMessage.remove(messageId);
                }
                return;
            }
            if (!"assistant".equals(role)) {
                return;
            }
            Map<String, Object> time = info.get("time") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : Map.of();
            boolean completed = time.get("completed") != null;
            if (!completed || messageId == null || !mergedMessages.add(messageId)) {
                return;
            }
            // Merge this completed step into the turn reply; persistence happens once at idle.
            mergeStepIntoTurn(messageId, usageFromTokens(info.get("tokens")));
        }

        /**
         * Folds one step's buffered text/tools/usage into the turn reply. Called when a step's
         * message completes normally, and again from {@link #flushTurn} for steps whose turn was
         * cut before their completion event (their parts live in the per-message buffers only).
         */
        private void mergeStepIntoTurn(String messageId, SessionUsage stepUsage) {
            synchronized (turnLock) {
                String content = joinedContent(messageId);
                List<ToolCall> stepTools = drainToolCalls(messageId);
                if (!content.isEmpty()) {
                    if (turnText.length() > 0) {
                        turnText.append("\n\n");
                    }
                    turnText.append(content);
                }
                if (!stepTools.isEmpty()) {
                    synchronized (turnTools) {
                        for (int i = 0; i < stepTools.size(); i++) {
                            ToolCall tc = stepTools.get(i);
                            turnTools.add(new TurnTool("m:" + messageId + ":" + i,
                                    tc.name(), tc.argumentsJson(), tc.resultJson(), "SUCCESS"));
                        }
                    }
                }
                // 时间线：按该消息内草稿槽的到达序 drain（思考/文本/工具交错保序），
                // 空文本/空槽丢弃；文本沿用 joinedContent 的相邻同文折叠（opencode 在步
                // 完成时用新 partId 重发全文，是回声不是新内容）。
                LinkedHashMap<String, PartSlot> slots = partsByMessage.remove(messageId);
                if (slots != null) {
                    String prevText = null;
                    for (PartSlot slot : slots.values()) {
                        if ("tool".equals(slot.kind)) {
                            turnParts.add(TurnPart.tool(slot.name, slot.inputJson,
                                    slot.output == null || slot.output.isBlank() ? null : slot.output));
                        } else if (slot.text.length() > 0) {
                            String t = slot.text.toString();
                            if ("text".equals(slot.kind) && t.equals(prevText)) {
                                continue;
                            }
                            turnParts.add("thinking".equals(slot.kind)
                                    ? TurnPart.thinking(t)
                                    : TurnPart.text(t));
                            if ("text".equals(slot.kind)) {
                                prevText = t;
                            }
                        }
                    }
                }
                if (stepUsage != null) {
                    turnUsage = (turnUsage == null ? SessionUsage.EMPTY : turnUsage).add(stepUsage);
                }
                if (!content.isEmpty() || !stepTools.isEmpty() || stepUsage != null || (slots != null && !slots.isEmpty())) {
                    turnHasNewContent = true;
                    assistantPersistedSinceSend = true;
                }
                log.info("opencode", "assistant.step-merged", "sessionId", sessionId,
                        "messageId", messageId, "chars", content.length(),
                        "toolCalls", stepTools.size());
            }
        }

        /** Degraded recovery flush: a turn that never reached {@code session.status=idle}
         *  (user abort, serve drop, superseded by a fresh send). See the two-arg overload. */
        void flushTurn(String reason) {
            flushTurn(reason, true);
        }

        /**
         * Persist the buffered turn (all steps' text + tool calls + summed usage) as ONE
         * assistant reply — the openchamber-style grouping of the idle path, reused here
         * so an unfinished turn survives a session switch/reload. Content already streamed
         * live; no chunks are emitted (recover-only persistence). No-op when the turn
         * buffer holds nothing. Recovery callers pass {@code degraded=true}.
         */
        void flushTurn(String reason, boolean degraded) {
            // 快照与清空必须在锁内完成：flush 可能被停机线程/错误路径调用，与 reader 线程的
            // step 合并并发。DB I/O（insertMessage / usage 回写）放到锁外，避免持锁阻塞。
            final String content;
            final List<ToolCall> tools = new ArrayList<>();
            final List<TurnPart> parts = new ArrayList<>();
            final SessionUsage usage;
            synchronized (turnLock) {
                // Drain steps that never reached their message.updated(completed) merge: an aborted
                // or dropped turn can be cut mid-step, leaving that step's text/tools only in the
                // per-message buffers. Without this the flush would silently skip them.
                for (String messageId : List.copyOf(messageParts.keySet())) {
                    if (!userMessages.contains(messageId)) {
                        mergeStepIntoTurn(messageId, null);
                    }
                }
                for (String messageId : List.copyOf(toolsByMessage.keySet())) {
                    if (!userMessages.contains(messageId)) {
                        mergeStepIntoTurn(messageId, null);
                    }
                }
                for (String messageId : List.copyOf(partsByMessage.keySet())) {
                    if (!userMessages.contains(messageId)) {
                        mergeStepIntoTurn(messageId, null);
                    }
                }
                if (!turnHasNewContent) {
                    return;
                }
                content = turnText.toString();
                synchronized (turnTools) {
                    for (TurnTool tt : turnTools) {
                        tools.add(new ToolCall(tt.name(), tt.inputJson(), tt.output()));
                    }
                    turnTools.clear();
                }
                parts.addAll(turnParts);
                turnParts.clear();
                usage = turnUsage;
                turnText.setLength(0);
                turnUsage = null;
                turnHasNewContent = false;
                assistantPersistedSinceSend = true;
            }
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                    sessionId, Role.ASSISTANT, content, tools, usage, degraded, clock.now(), parts));
            log.info("opencode", degraded ? "turn.persisted-degraded" : "turn.persisted",
                    "sessionId", sessionId, "reason", reason,
                    "chars", content.length(), "toolCalls", tools.size());
            if (usage != null) {
                Session latest = sessions.find(sessionId).orElse(null);
                if (latest != null) {
                    Session updated = latest.withCumulativeUsage(latest.cumulativeUsage().add(usage));
                    sessions.update(updated);
                    writeback(updated);
                }
            }
        }

        private void handleSessionStatus(Map<String, Object> props) {
            if (!cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            Map<String, Object> status = props.get("status") instanceof Map<?, ?> s
                    ? (Map<String, Object>) s : Map.of();
            if ("idle".equals(str(status.get("type")))) {
                // Turn ended: persist the whole turn (all steps' text + tool calls + summed
                // usage) as ONE assistant reply — openchamber-style grouping.
                flushTurn("idle", false);
                // If nothing was produced and an error was buffered, persist it so the
                // failure is visible after reload instead of living only in the SSE stream.
                String errName = pendingErrorName;
                String errMsg = pendingErrorMessage;
                if (!assistantPersistedSinceSend && errName != null) {
                    pendingErrorName = null;
                    pendingErrorMessage = null;
                    String body = (errName + (errMsg == null ? "" : ": " + errMsg));
                    sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                            sessionId, Role.ERROR, body, List.of(), null, true, clock.now()));
                    log.error("opencode", "turn.failed", "sessionId", sessionId,
                            "errorName", errName, "errorMessage", errMsg);
                }
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastDoneAt > 300) {
                    lastDoneAt = nowMs;
                    emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, cliSessionId, clock.now()));
                }
                // 回合终点：只有“已受理”的回合才能消耗 busy 计数（幂等——key 不存在/重复 idle 均安全）。
                releaseBusyOnTurnEnd(sessionId);
            }
            // busy/retry drive no chat chunks; the UI spinner is bounded by done/error.
        }

        @SuppressWarnings("unchecked")
        private void handleSessionError(Map<String, Object> props) {
            if (props.get("sessionID") != null && !cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            Map<String, Object> error = props.get("error") instanceof Map<?, ?> e
                    ? (Map<String, Object>) e : Map.of();
            String name = str(error.get("name"));
            String message = str(error.get("message"));
            pendingErrorName = name == null ? "OPENCODE_ERROR" : name;
            pendingErrorMessage = message;
            log.warn("opencode", "session.error", "sessionId", sessionId,
                    "errorName", pendingErrorName, "errorMessage", message);
            emitChunk(sessionId, new SessionStreamChunk.ErrorChunk(sessionId,
                    pendingErrorName, message, clock.now()));
            // 回合以 error 收场时可能永远等不到后续 idle（provider 中断、上游静默挂死）：
            // 立即把已缓冲的回合内容降级落库。缓冲为空则 no-op，随后的 idle 仍能把
            // pendingError 落成 ERROR 行；缓冲非空则该行与内容并存，不再整体丢失。
            flushTurn("session-error", true);
            // session.error 同样是回合终止信号：无 idle 跟随（reader 恰在此后掉线）也必须释放，
            // 否则 busy 泄漏为常驻 1；陈旧 error 帧因无受理记录被上面的守卫忽略。
            releaseBusyOnTurnEnd(sessionId);
        }

        /**
         * session.updated: carries the full session info ({info:{id, title, ...}}). opencode's
         * built-in title agent writes a real title after the first user turn; persist it so the
         * workbench session list can show it. Default placeholders ("New session - <ISO>") are
         * ignored — the UI falls back to a local time label until a real title arrives.
         */
        private void handleSessionUpdated(Map<String, Object> props) {
            Map<String, Object> info = props.get("info") instanceof Map<?, ?> i
                    ? castMap(i) : null;
            if (info == null || !cliSessionId.equals(str(info.get("id")))) {
                return;
            }
            String title = str(info.get("title"));
            if (title == null || title.isBlank() || isDefaultOpencodeTitle(title)) {
                return;
            }
            Session latest = sessions.find(sessionId).orElse(null);
            if (latest == null || title.equals(latest.title())) {
                return;
            }
            sessions.update(latest.withTitle(title));
            log.info("opencode", "session.title-synced", "sessionId", sessionId, "title", title);
            // 同时通过流通道把新标题立刻广播给前端，sidebar 无需等回合结束即可替换占位标签。
            emitChunk(sessionId, new SessionStreamChunk.TitleChunk(sessionId, title, clock.now()));
        }

        /** Mirrors opencode's Session.isDefaultTitle ("New session - " / "Child session - " + ISO stamp). */
        private static boolean isDefaultOpencodeTitle(String title) {
            return title.matches("^(New session - |Child session - )\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
        }

        /**
         * permission.asked: property map IS the request ({id, sessionID, permission, patterns,
         * always, metadata, tool?}). Record it for the /permissions endpoint, surface it to the
         * UI, and auto-answer "once" when this session's auto-accept switch is on.
         */
        @SuppressWarnings("unchecked")
        private void handlePermissionAsked(Map<String, Object> props) {
            if (!cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            String permissionId = str(props.get("id"));
            if (permissionId == null) {
                return;
            }
            PermissionRequest request = permissionFromProps(props);
            pendingPermissions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                    .put(permissionId, request);
            emitChunk(sessionId, new SessionStreamChunk.PermissionAskedChunk(sessionId, request, clock.now()));
            Session latest = sessions.find(sessionId).orElse(null);
            if (latest != null && latest.permissionAutoAccept()) {
                // Defer the HTTP call off the reader thread so it cannot stall SSE reads.
                executor.submit(() -> autoAllow(sessionId, permissionId, port));
            }
        }

        /** permission.replied: {sessionID, requestID, reply}. Drop the pending entry, notify the UI. */
        private void handlePermissionReplied(Map<String, Object> props) {
            if (!cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            String requestId = str(props.get("requestID"));
            if (requestId == null) {
                return;
            }
            removePending(sessionId, requestId);
            emitChunk(sessionId, new SessionStreamChunk.PermissionRepliedChunk(
                    sessionId, requestId, str(props.get("reply")), false, clock.now()));
        }

        /**
         * question.asked (question 工具): the property map IS the request ({id, sessionID,
         * questions[], tool?}). Record it for the /questions endpoint and surface an interactive
         * card to the UI — questions are always for the human, never auto-answered.
         */
        private void handleQuestionAsked(Map<String, Object> props) {
            if (!cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            QuestionRequest request = questionFromProps(props);
            if (request.requestId() == null) {
                return;
            }
            pendingQuestions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                    .put(request.requestId(), request);
            log.info("opencode", "question.asked", "sessionId", sessionId,
                    "requestId", request.requestId(), "questions", request.questions().size());
            emitChunk(sessionId, new SessionStreamChunk.QuestionAskedChunk(sessionId, request, clock.now()));
        }

        /** question.replied / question.rejected: {sessionID, requestID, answers?}. */
        private void handleQuestionResolved(Map<String, Object> props, boolean rejected) {
            if (!cliSessionId.equals(str(props.get("sessionID")))) {
                return;
            }
            String requestId = str(props.get("requestID"));
            if (requestId == null) {
                return;
            }
            removePendingQuestion(sessionId, requestId);
            emitChunk(sessionId, new SessionStreamChunk.QuestionRepliedChunk(
                    sessionId, requestId, rejected, answerLists(props.get("answers")), false, clock.now()));
        }

        /** Server-side auto-allow: answer "once" on the user's behalf, then reflect the outcome. */
        private void autoAllow(String sessionId, String permissionId, int port) {
            try {
                HttpResponse<String> resp = post("http://127.0.0.1:" + port + "/permission/"
                                + permissionId + "/reply",
                        "{\"reply\":\"once\"}", PERMISSION_HTTP_TIMEOUT);
                if (resp.statusCode() / 100 != 2) {
                    log.warn("opencode", "permission.auto-allow-rejected", "sessionId", sessionId,
                            "permissionId", permissionId, "status", resp.statusCode());
                    return;
                }
                removePending(sessionId, permissionId);
                emitChunk(sessionId, new SessionStreamChunk.PermissionRepliedChunk(
                        sessionId, permissionId, "once", true, clock.now()));
            } catch (Exception e) {
                // Failed auto-allow falls back to the user answering the card manually.
                log.warn("opencode", "permission.auto-allow-failed", "sessionId", sessionId,
                        "permissionId", permissionId, "error", e.getClass().getSimpleName());
            }
        }

        private List<ToolCall> drainToolCalls(String messageId) {
            LinkedHashMap<String, ToolCallState> calls = toolsByMessage.remove(messageId);
            if (calls == null || calls.isEmpty()) {
                return List.of();
            }
            List<ToolCall> out = new ArrayList<>(calls.size());
            for (ToolCallState st : calls.values()) {
                out.add(new ToolCall(st.name, st.argumentsJson, st.resultJson));
            }
            return out;
        }

        /** 时间线草稿槽：首次到达即定序，后续更新原位替换（文本快照/思考增量/工具终态）。 */
        private PartSlot draft(String messageId, String key, String kind) {
            LinkedHashMap<String, PartSlot> slots =
                    partsByMessage.computeIfAbsent(messageId, k -> new LinkedHashMap<>());
            PartSlot slot = slots.get(key);
            if (slot == null) {
                slot = new PartSlot(kind);
                slots.put(key, slot);
            }
            return slot;
        }

        private PartSlot toolDraft(String messageId, String callId) {
            return draft(messageId, callId, "tool");
        }

        /**
         * Joins the per-part snapshot texts into the persisted body. Adjacent byte-identical
         * parts are collapsed: opencode re-announces a finished step's final text under a fresh
         * part id, and that echo is not real content.
         */
        private String joinedContent(String messageId) {
            LinkedHashMap<String, String> parts = messageParts.remove(messageId);
            if (parts == null || parts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (String t : parts.values()) {
                if (t == null || t.isEmpty() || t.equals(prev)) {
                    continue;
                }
                sb.append(t);
                prev = t;
            }
            return sb.toString();
        }
    }

    /** Mutable accumulator for one upstream tool call; confined to the reader thread. */
    static final class ToolCallState {
        String name = "unknown";
        String argumentsJson = "";
        String resultJson;
    }

    /**
     * One timeline draft slot ({@code partsByMessage} value): kind is "text" (snapshot text,
     * replace-not-append), "thinking" (delta-appended) or "tool" (name/input/output upserted
     * to the final state as part updates stream in). Confined to the reader thread.
     */
    static final class PartSlot {
        final String kind;
        StringBuilder text = new StringBuilder();
        String name;
        String inputJson;
        String output;

        PartSlot(String kind) {
            this.kind = kind;
        }
    }

    private void ensureUpstream(String sessionId, int port, String cliSessionId) {
        Upstream up = upstreams.get(sessionId);
        if (up == null) {
            up = new Upstream(sessionId, port, cliSessionId);
            upstreams.put(sessionId, up);
            up.start();
        }
    }

    private Upstream stopUpstream(String sessionId) {
        Upstream up = upstreams.remove(sessionId);
        if (up != null) {
            up.stop();
        }
        return up;
    }

    private void checkStalledUpstreams() {
        for (Upstream up : upstreams.values()) {
            if (up.stale()) {
                // Closing the body unblocks readLine(); the reader loop reconnects with
                // Last-Event-ID, exactly like OpenChamber's stall recovery.
                log.warn("opencode", "upstream.stall-force-reconnect", "sessionId", up.sessionId,
                        "silentMs", System.currentTimeMillis() - up.lastEventAt);
                up.closeBody();
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // History backfill: reconcile gate's persisted history against opencode's own store
    // -------------------------------------------------------------------------------------------

    /**
     * 复活后从 opencode 自有存储回填 gate 缺失的历史（T-113 教训：回合缓冲只在内存里、依赖
     * session.status=idle 落库；后端重启或回合挂死会让整个缓冲丢失，而 opencode 侧全量消息
     * 完好）。通过 {@code GET /session/{id}/message} 拉取全量 [{info, parts}]，按时间截点
     * 跳过 gate 已落库的内容，再把余下部分按回合形态落库：
     *
     * <ul>
     *   <li>assistant：只收 {@code time.completed} 不为空的已完结消息；相邻消息累积为一个
     *       回合行（text 聚合 + 工具聚合 + usage 求和，与 idle 落库形态一致，degraded=true）。
     *       截点 = gate 已有 ASSISTANT 行的最新时间——失败的 send（ERROR 行）不会挡住
     *       截点之前的丢失回合被找回。</li>
     *   <li>user：截点 = gate 所有行的最新时间 + 发送宽限（gate 自己落 USER 行早于
     *       opencode 落用户消息零点几秒，跳过即可；用户直接 opencode -s 发的消息会被收录）。</li>
     * </ul>
     *
     * <p>任何异常只降级告警、不阻断复活。
     */
    @SuppressWarnings("unchecked")
    private void backfillFromServe(String sessionId, int port, String cliSessionId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/session/" + cliSessionId + "/message"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("opencode", "backfill.unavailable", "sessionId", sessionId,
                        "status", resp.statusCode());
                return;
            }
            Object parsed = MiniJson.parse(resp.body().trim());
            if (!(parsed instanceof List<?> list)) {
                return;
            }
            long assistantCutoffMs = 0L;
            long anyCutoffMs = 0L;
            for (SessionMessage m : sessions.findMessages(sessionId)) {
                long at = m.timestamp().toEpochMilli();
                anyCutoffMs = Math.max(anyCutoffMs, at);
                if (m.role() == Role.ASSISTANT) {
                    assistantCutoffMs = Math.max(assistantCutoffMs, at);
                }
            }
            // gate 落 USER 行发生在 prompt 送达前，上游 user 消息时间戳必然晚几百毫秒——
            // 用宽限吃掉这段延迟，已落库的 user 消息不会被重复收录。
            long userCutoffMs = anyCutoffMs + USER_BACKFILL_GRACE_MS;

            BackfillTurn turn = new BackfillTurn();
            SessionUsage totalUsage = null;
            int rows = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawEntry)) {
                    continue;
                }
                Map<String, Object> entry = castMap(rawEntry);
                if (!(entry.get("info") instanceof Map<?, ?> rawInfo)) {
                    continue;
                }
                Map<String, Object> info = castMap(rawInfo);
                if (!cliSessionId.equals(str(info.get("sessionID")))) {
                    continue;
                }
                Map<String, Object> time = info.get("time") instanceof Map<?, ?> t
                        ? castMap(t) : Map.of();
                Long created = longOrNull(time.get("created"));
                if (created == null) {
                    continue;
                }
                List<Object> parts = entry.get("parts") instanceof List<?> p
                        ? (List<Object>) p : List.of();
                String role = str(info.get("role"));
                if ("user".equals(role)) {
                    if (created <= userCutoffMs) {
                        continue;
                    }
                    rows += persistBackfillTurn(sessionId, turn);
                    String content = backfillUserText(parts);
                    if (!content.isBlank()) {
                        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                                sessionId, Role.USER, content, List.of(), null, false, clock.now()));
                        rows++;
                    }
                } else if ("assistant".equals(role)
                        && time.get("completed") != null
                        && created > assistantCutoffMs) {
                    accumulateBackfillParts(turn, parts);
                    SessionUsage u = usageFromTokens(info.get("tokens"));
                    if (u != null) {
                        turn.usage = (turn.usage == null ? SessionUsage.EMPTY : turn.usage).add(u);
                        totalUsage = (totalUsage == null ? SessionUsage.EMPTY : totalUsage).add(u);
                    }
                }
            }
            rows += persistBackfillTurn(sessionId, turn);
            if (totalUsage != null) {
                Session latest = sessions.find(sessionId).orElse(null);
                if (latest != null) {
                    Session updated = latest.withCumulativeUsage(latest.cumulativeUsage().add(totalUsage));
                    sessions.update(updated);
                    writeback(updated);
                }
            }
            log.info("opencode", "backfill.done", "sessionId", sessionId, "rows", rows,
                    "assistantCutoffMs", assistantCutoffMs, "userCutoffMs", userCutoffMs);
        } catch (Exception e) {
            log.warn("opencode", "backfill.failed", "sessionId", sessionId,
                    "error", e.getClass().getSimpleName());
        }
    }

    /** gate 落 USER 行早于 opencode 落用户消息的最大预期延迟；宽限内的上游 user 消息视为已收录。 */
    private static final long USER_BACKFILL_GRACE_MS = 10_000L;

    /** 把一个累积好的回填回合落成 ASSISTANT 行（有内容才落），返回落库行数。 */
    private int persistBackfillTurn(String sessionId, BackfillTurn turn) {
        if (!turn.hasContent()) {
            return 0;
        }
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                sessionId, Role.ASSISTANT, turn.text.toString(),
                List.copyOf(turn.tools), turn.usage, true, clock.now()));
        turn.text.setLength(0);
        turn.tools.clear();
        turn.usage = null;
        turn.lastText = null;
        return 1;
    }

    /** 把一条 upstream assistant 消息的 final parts 并入回填回合：text 聚合，tool 记账。 */
    private static void accumulateBackfillParts(BackfillTurn turn, List<Object> parts) {
        for (Object p : parts) {
            if (!(p instanceof Map<?, ?> rawPart)) {
                continue;
            }
            Map<String, Object> part = castMap(rawPart);
            String type = str(part.get("type"));
            if ("text".equals(type)) {
                String text = str(part.get("text"));
                if (text != null && !text.isEmpty() && !text.equals(turn.lastText)) {
                    if (turn.text.length() > 0) {
                        turn.text.append("\n\n");
                    }
                    turn.text.append(text);
                    turn.lastText = text;
                }
            } else if ("tool".equals(type)) {
                Map<String, Object> state = part.get("state") instanceof Map<?, ?> st
                        ? castMap(st) : Map.of();
                String name = str(part.get("tool"));
                turn.tools.add(new ToolCall(name == null ? "unknown" : name,
                        jsonValue(state.get("input")), str(state.get("output"))));
            }
        }
    }

    /** user 消息回填正文：拼接 text parts（file parts 忽略，与实时链路的落库一致）。 */
    private static String backfillUserText(List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (!(p instanceof Map<?, ?> rawPart)) {
                continue;
            }
            Map<String, Object> part = castMap(rawPart);
            if (!"text".equals(str(part.get("type")))) {
                continue;
            }
            String text = str(part.get("text"));
            if (text != null && !text.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** 回填回合累积器（单线程使用：ensureServe 的复活锁内）。 */
    private static final class BackfillTurn {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> tools = new ArrayList<>();
        SessionUsage usage;
        String lastText;

        boolean hasContent() {
            return text.length() > 0 || !tools.isEmpty();
        }
    }

    private SessionUsage usageFromTokens(Object tokensObj) {
        if (!(tokensObj instanceof Map<?, ?> tokens)) {
            return null;
        }
        Long input = longOrNull(tokens.get("input"));
        Long output = longOrNull(tokens.get("output"));
        Long reasoning = longOrNull(tokens.get("reasoning"));
        long cacheRead = nvl(longOrNull(cacheTokens(tokens).get("read")));
        long cacheWrite = nvl(longOrNull(cacheTokens(tokens).get("write")));
        if (input == null && output == null && reasoning == null) {
            return null;
        }
        long total = nvl(input) + nvl(output) + nvl(reasoning) + cacheRead + cacheWrite;
        return new SessionUsage(input, output, total);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cacheTokens(Object tokens) {
        if (tokens instanceof Map<?, ?> m && m.get("cache") instanceof Map<?, ?> c) {
            return (Map<String, Object>) c;
        }
        return Map.of();
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    @SuppressWarnings("unchecked")
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** Compact JSON encoding for arbitrary decoded-MiniJson values (tool inputs etc.). */
    private static String jsonValue(Object v) {
        StringBuilder sb = new StringBuilder();
        appendJsonValue(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonValue(sb, String.valueOf(e.getKey()));
                sb.append(':');
                appendJsonValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
        }
    }

    private static Long longOrNull(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static GateTask progress(GateTask task, int percent, String label) {
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.RUNNING, task.startedAt(), null,
                "{\"percent\":" + percent + ",\"label\":\"" + label + "\"}", null);
    }

    private static GateTask success(GateTask task, String resultJson) {
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.SUCCEEDED, task.startedAt(), null, resultJson, null);
    }

    private static GateTask fail(GateTask task, Throwable e) {
        int code = e instanceof GateException ge ? ge.code().code() : 70;
        String name = e instanceof GateException ge ? ge.code().name() : "INTERNAL";
        String message = e instanceof GateException ge2 ? ge2.getMessage() : "internal error";
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.FAILED, task.startedAt(), null, null,
                "{\"error_code\":" + code + ",\"error\":\"" + name
                        + "\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    // -------------------------------------------------------------------------------------------
    // Serve process management
    // -------------------------------------------------------------------------------------------

    /**
     * 启动期兜底清扫：登记文件（sweepOrphans）只记得「登记过的」serve，历史构建、登记文件
     * 丢失或其诞生前遗留的孤儿永远清不到——新建会话会撞上 stale port 报错（49153-49156 连环
     * 失败的实际案例）。这里扫整个进程表找 opencode 可执行体，对命令行是 {@code serve --port P}
     * 且 P 落在 session.port_range 内的进程收割（与 ServePidRegistry 同一安全判据：命令行含
     * opencode 才动手；用户自己的交互式 opencode / 端口段外的进程绝不碰）。仅适配器构造时
     * 执行一次，运行中的会话进程此时尚未启动，无误杀窗口。
     */
    private void sweepRangeOrphans() {
        if (opencodeExecutable == null || opencodeExecutable.isBlank()) {
            return;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<Long> candidates = new ArrayList<>();
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            // name/command 在 Windows 上可用；arguments() 在 Windows 拿不到（null），
            // 所以 serve 形态要靠下面的外部命令行查询确认。
            String command = ph.info().command().orElse("");
            if (command.contains("opencode")) {
                candidates.add(ph.pid());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        List<Long> orphans = new ArrayList<>();
        for (long pid : candidates) {
            String cmdline = processCommandLine(pid, windows);
            if (cmdline == null || !cmdline.toLowerCase().contains("opencode")) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("serve\\s+--port\\s+(\\d+)").matcher(cmdline);
            if (!m.find()) {
                continue; // 交互式 opencode / 非 serve 形态，放过
            }
            int port = Integer.parseInt(m.group(1));
            if (port >= ports.min() && port < ports.min() + ports.size()) {
                orphans.add(pid);
            }
        }
        if (orphans.isEmpty()) {
            return;
        }
        int killed = ServePidRegistry.reapIfOpencode(orphans);
        log.warn("opencode", "start.range-orphan-sweep", "killed", killed,
                "candidates", orphans.size());
    }

    /** Best-effort command line lookup: wmic（Windows）/ ps（Unix），失败返回 null。 */
    private String processCommandLine(long pid, boolean windows) {
        try {
            Process p = windows
                    ? new ProcessBuilder("wmic", "process", "where", "ProcessId=" + pid,
                            "get", "CommandLine").start()
                    : new ProcessBuilder("ps", "-p", String.valueOf(pid), "-o", "args=").start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                StringBuilder sb = new StringBuilder();
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty() || line.trim().equalsIgnoreCase("CommandLine")) {
                        continue;
                    }
                    sb.append(line.trim()).append(' ');
                }
                return sb.length() == 0 ? null : sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 占用拒绝的统一前缀：acquireUsablePort 的跨步重试据此识别「可跳过」的端口失败。 */
    private static final String PORT_BUSY_PREFIX = "port busy: ";

    private GateException portBusy(int port, String detail) {
        return new GateException(GateErrorCode.GATE_ERROR_IO, PORT_BUSY_PREFIX + port + " " + detail);
    }

    private boolean isPortBusyRefusal(Throwable t) {
        return t instanceof GateException ge
                && ge.getMessage() != null
                && ge.getMessage().startsWith(PORT_BUSY_PREFIX);
    }

    /** Something already answers /health on the port（孤儿 serve 或外来进程）. */
    private boolean probePortOccupied(int port) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofMillis(500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception ignored) {
            return false; // nothing listening -> genuinely free
        }
    }

    /**
     * 端口择位：从分配器取一个端口并确保它真的可用。被占时先尝试孤儿自愈（收割占着该端口
     * 的 opencode serve，上次运行被强杀时必然发生）；收割失败（外来进程占用）返回 null，
     * 调用方释放并按 2^n 跨步换下一个候选。
     */
    private int acquireUsablePort() {
        int skip = 1;
        int attempts = 0;
        while (true) {
            int port = ports.allocate();
            if (opencodeExecutable == null || opencodeExecutable.isBlank()) {
                return port; // 无 CLI 可执行体：不 spawn，端口仅作占位
            }
            if (!probePortOccupied(port)) {
                return port;
            }
            healStaleServe(port);
            if (!probePortOccupied(port)) {
                return port; // 孤儿已收割，端口已释放（socket 关闭略有延迟，waitHealthy 会重试）
            }
            // 外来进程占用：释放并按 1,2,4,8… 跨步跳过当前游标邻域的坏端口。
            // allocate 本身已消耗当前格，跨 n 格只需再推 n-1 格。
            ports.release(port);
            log.warn("opencode", "start.port-busy-skip", "port", port, "skip", skip);
            ports.skip(skip - 1);
            skip = Math.min(skip * 2, 4096);
            if (++attempts >= 32) {
                throw portBusy(port, "port range exhausted after " + attempts + " skipping attempts");
            }
        }
    }

    /**
     * 分配时自愈：找到 {@code serve --port <port>} 的 opencode 进程并收割（仅 OUR 端口段内、
     * 命令行含 opencode 才动手）。返回是否收割成功——失败意味着占用者是外来进程。
     */
    private void healStaleServe(int port) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            if (!ph.info().command().orElse("").contains("opencode")) {
                continue;
            }
            String cmdline = processCommandLine(ph.pid(), windows);
            if (cmdline == null) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("serve\\s+--port\\s+(\\d+)").matcher(cmdline);
            if (m.find() && Integer.parseInt(m.group(1)) == port) {
                ServePidRegistry.reapIfOpencode(List.of(ph.pid()));
                log.warn("opencode", "start.stale-serve-reaped", "port", port, "pid", ph.pid());
                try {
                    Thread.sleep(200); // socket 关闭到可重新 bind 有毫秒级延迟
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }

    private void spawnServe(int port, String clonePath, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(opencodeExecutable, "serve",
                    "--port", String.valueOf(port), "--hostname", "127.0.0.1");
            if (clonePath != null) {
                pb.directory(Path.of(clonePath).toFile());
            }
            // MCP provisioning: without this the agent has no presubmit_create and cannot file a
            // presubmit itself (the T-110 session ended with "仅有 Open Design 相关工具"). The
            // config registers the gate MCP server under the clone's .git/ so it never appears in
            // the ticket diff; OPENCODE_CONFIG MERGES with the user's global opencode config
            // (their providers/models survive), and the domain token rides in the MCP child's
            // environment, never argv.
            provisionGateMcp(pb, clonePath, env);
            // A gate backend launched from inside an OpenChamber/OpenCode-managed shell inherits
            // that shell's server wiring; OPENCODE_SERVER_PASSWORD in particular makes every child
            // serve demand Bearer auth our adapter never sends (all requests 401). The spawned
            // serve must be a clean-slate instance. (OPENCODE_CONFIG_CONTENT is inline-config
            // override — removed so only our file-based OPENCODE_CONFIG applies.)
            for (String key : List.of("OPENCODE_SERVER_PASSWORD", "OPENCODE_CONFIG_CONTENT",
                    "OPENCODE_BINARY", "OPENCODE_PID", "OPENCODE")) {
                pb.environment().remove(key);
            }
            pb.redirectErrorStream(true);
            // serve 遗言落盘：此前 DISCARD 把 stdout/stderr 全部丢弃，进程死亡（崩溃/OOM/
            // 被杀）后无任何尸检线索。改为追加到 gate-home/serve-logs/serve-<port>.log——
            // opencode 崩溃栈、Node OOM、端口冲突等原因都留在文件里可查。日志目录从
            // gateToml（= gate-home/gate.toml）的父目录推导，无需改构造签名；测试装配
            // gateToml=null 时退回系统临时目录。追加模式保留跨重启的多次遗言。
            Path serveLog = serveLogPath(port);
            try {
                java.nio.file.Files.createDirectories(serveLog.getParent());
            } catch (java.io.IOException ignored) {
                // 日志文件建不出来时退回 DISCARD，不阻断 spawn
            }
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(serveLog.toFile()));
            Process p = pb.start();
            serveProcesses.put(port, p);
            pidRegistry.record(p.pid());
            // onExit 钩子：进程退出（正常/被杀/崩溃）即落一条带 exit code 的记录——
            // 与 serve-<port>.log 的遗言互为尸检证据；code!=0 走 warn 级别。
            p.onExit().thenAccept(ph -> {
                int code = ph.exitValue();
                if (code == 0) {
                    log.info("opencode", "serve.exited", "port", port, "pid", p.pid(), "exitCode", code);
                } else {
                    log.warn("opencode", "serve.exited", "port", port, "pid", p.pid(),
                            "exitCode", code, "logFile", serveLog.toString());
                }
            });
            log.info("opencode", "serve.spawned", "port", port,
                    "pid", p.pid(), "clonePath", clonePath);
        } catch (IOException e) {
            log.error("opencode", "serve.spawn-failed", "port", port, "error", String.valueOf(e));
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot spawn opencode serve on port " + port, e);
        }
    }

    /**
     * serve 进程 stdout/stderr 的遗言文件（追加模式）：gate-home/serve-logs/serve-&lt;port&gt;.log。
     * 目录从 gateToml（= gate-home/gate.toml）的父目录推导，装配零改动；测试装配
     * gateToml=null 时退回系统临时目录（遗言仍有处可去，只是不在 gate-home）。
     */
    private Path serveLogPath(int port) {
        Path base = gateToml != null && gateToml.getParent() != null
                ? gateToml.getParent()
                : java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "gate-serve-logs");
        return base.resolve("serve-logs").resolve("serve-" + port + ".log");
    }

    /**
     * Writes the per-session opencode config registering the gate MCP server and points the child
     * at it via OPENCODE_CONFIG. No-op (session starts without gate tools, as before) when
     * provisioning is disabled ({@code gateToml} null) or the start request carried no domain
     * token — the token is minted per session by the web layer and arrives via StartRequest.env.
     */
    private void provisionGateMcp(ProcessBuilder pb, String clonePath, Map<String, String> env) {
        String token = env == null ? null : env.get(gate.adapters.mcp.McpServer.TOKEN_ENV);
        if (gateToml == null || clonePath == null || token == null || token.isBlank()) {
            return;
        }
        try {
            Path configFile = Path.of(clonePath).resolve(".git").resolve("gate-context")
                    .resolve("opencode-config.json");
            java.nio.file.Files.createDirectories(configFile.getParent());
            String json = GateMcpProvisioning.opencodeConfigJson(
                    GateMcpProvisioning.serveArgv(gateToml), token);
            java.nio.file.Files.writeString(configFile, json, StandardCharsets.UTF_8);
            pb.environment().put("OPENCODE_CONFIG", configFile.toAbsolutePath().toString());
            log.info("opencode", "serve.mcp-provisioned", "config", configFile.toString());
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot write opencode MCP config for clone " + clonePath, e);
        }
    }

    private void waitHealthy(int port) {
        long deadline = System.nanoTime() + startTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            // If the spawned server exited (bad flags, missing runtime, port collision), fail fast with
            // a real reason instead of polling the full window for a process that can never answer.
            Process proc = serveProcesses.get(port);
            if (proc != null && !proc.isAlive()) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "opencode serve exited before becoming healthy on port " + port
                                + " (exit " + proc.exitValue() + ")");
            }
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .timeout(Duration.ofMillis(500)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // server not up yet
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GateException(GateErrorCode.GATE_ERROR_IO, "interrupted waiting for opencode", e);
            }
        }
        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                "opencode serve did not become healthy on port " + port
                        + " within " + startTimeout.toSeconds() + "s");
    }

    private String createSession(int port) {
        // The serve process is started with the ticket clone as its working directory. Current
        // OpenCode accepts only session metadata here; directory routing belongs to the server
        // instance, not the session-create body.
        String body = "{}";
        HttpResponse<String> resp = post("http://127.0.0.1:" + port + "/session", body);
        if (resp.statusCode() / 100 != 2) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "opencode /session failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        Object parsed = MiniJson.parse(resp.body().trim());
        if (parsed instanceof Map<?, ?> m) {
            Object id = m.get("id");
            if (id != null) {
                return String.valueOf(id);
            }
        }
        throw new GateException(GateErrorCode.GATE_ERROR_IO,
                "opencode /session response missing id: " + resp.body());
    }

    private HttpResponse<String> post(String url, String body) {
        return post(url, body, Duration.ofSeconds(30));
    }

    private HttpResponse<String> post(String url, String body, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "opencode HTTP call failed: " + url, e);
        }
    }

    /** @return true only when opencode acknowledged with a 2xx within the timeout. */
    private boolean postQuietly(String url, String body, Duration timeout) {
        try {
            return post(url, body, timeout).statusCode() / 100 == 2;
        } catch (Exception e) {
            // abort best-effort
            return false;
        }
    }

    private void killProcess(Integer port) {
        if (port == null) {
            return;
        }
        Process p = serveProcesses.remove(port);
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }
}
