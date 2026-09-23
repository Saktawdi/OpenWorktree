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
 * <p>Streaming architecture: prompts are fired with
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
 * stalled stream, following the same recovery shape as the upstream reader.
 */
public final class OpenCodeServeAdapter implements AgentSessionPort {

    /** Force-reconnect an upstream whose stream has been silent longer than this. */
    static final long UPSTREAM_STALL_TIMEOUT_MS = 90_000L;
    /** Delay between upstream reconnect attempts after a drop. */
    static final long UPSTREAM_RECONNECT_DELAY_MS = 2_000L;
    /** Serve 死亡判定：上游连续重连失败达到该次数即触发会话自愈（healDeadServe）。 */
    static final int UPSTREAM_HEAL_AFTER_FAILED_CONNECTS = 3;
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
                (resp, healed) -> {
                    if (resp.statusCode() == 404) {
                        if (healed) {
                            // 自愈后的全新 serve 不持有旧实例的待决授权：本地关卡片即可，
                            // 用户的允许意图对已死的工具调用无处落地（下个回合重新触发时
                            // 会再次请求授权）。
                            log.info("opencode", "permission.reply-lost", "sessionId", sessionId,
                                    "permissionId", permissionId);
                            emitChunk(sessionId, new SessionStreamChunk.PermissionRepliedChunk(
                                    sessionId, permissionId, response, false, clock.now()));
                            return;
                        }
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
                                  String ref, java.util.function.BiConsumer<HttpResponse<String>, Boolean> check) {
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
        boolean healed = false;
        if (resp == null) {
            // 端口缺失或 POST 失败：serve 大概率已死。失效端口先清理（否则 ensureServe
            // 会盲返回旧端口，重试等于没修），再按 send 路径同一套懒复活拉起新 serve
            // 后重试一次；复活本身失败则把原始错误上抛，语义与旧行为一致。
            try {
                if (port != null && !serveHealthy(port)) {
                    log.warn("opencode", "reply.serve-dead-heal", "sessionId", sessionId,
                            "port", port);
                    healDeadServe(sessionId, port);
                    healed = true;
                } else if (port == null) {
                    // 端口映射已丢（reader 自愈/后端重启清理过）：ensureServe 复活的是
                    // 全新 serve 实例，旧实例的待决卡片（权限/提问）不可能还在。
                    healed = true;
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
        check.accept(resp, healed);
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

    /**
     * serve 进程死亡的自愈（reader 重连死循环的出口）：在途回合按 degraded 冲刷落库、
     * 释放随回合蒸发的 busy 计数、给浏览器补发 DoneChunk（否则 UI 转圈到看门狗兜底、
     * 排队消息永远等不到 busy→idle 边沿），再清理死进程与端口映射。serve 本体不在这里
     * 复活——下次使用（发消息/应答卡片）由 ensureServe 懒复活，避免为废弃会话常驻空转
     * 进程。持 per-session 复活锁，与 reply 自愈/send 路径互斥。
     *
     * @return true = 本调用完成了自愈（或发现已被其他路径自愈），reader 可以终止；
     *         false = serve 实际存活（如仅限流/SSE 抖动），reader 继续重连。
     */
    private boolean healDeadServe(String sessionId, int deadPort) {
        Object lock = resurrectLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            Integer current = sessionPorts.get(sessionId);
            Upstream up = upstreams.get(sessionId);
            if (current == null && up == null) {
                return true; // 已被其他路径自愈过，无需重复
            }
            if (current != null && current != deadPort) {
                return true; // 已复活到新端口
            }
            if (serveHealthy(deadPort)) {
                return false; // 进程仍存活：只是 SSE 抖动，保持既有重连语义
            }
            log.warn("opencode", "serve.dead-heal", "sessionId", sessionId, "port", deadPort,
                    "failedConnects", UPSTREAM_HEAL_AFTER_FAILED_CONNECTS);
            if (up != null) {
                // 冲刷半截回合必须先于摘除：turnHasNewContent 只在此处还有机会落库。
                up.flushTurn("serve-dead");
            }
            // 进程死亡 = 所有在途回合的 idle/error 终点一起蒸发，busy 全量清零
            // （与 abort 的兜底语义一致，不用逐次 decrement）。
            inFlightCounts.remove(sessionId);
            acceptedSinceRelease.remove(sessionId);
            String cliSessionId = sessions.find(sessionId).map(Session::cliSessionId).orElse(null);
            emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, cliSessionId, clock.now()));
            stopUpstream(sessionId);
            killProcess(deadPort);
            ports.release(deadPort);
            sessionPorts.remove(sessionId, deadPort);
            return true;
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
                requestId, (resp, healed) -> {
                    if (resp.statusCode() == 404) {
                        if (healed) {
                            // 自愈后的全新 serve 不持有旧实例的提问回合：用户的回答无处落地，
                            // 静默吞掉会让会话永久僵死（busy 无终点、排队消息无法泵出）。
                            // 降级为普通消息触发新回合——答案随消息送达模型，残余的待决
                            // 授权由 send 路径的 rejectPendingPermissions 兜底清理。
                            String digest = questionAnswerDigest(sessionId, requestId, answers);
                            log.info("opencode", "question.reply-lost-resend", "sessionId", sessionId,
                                    "requestId", requestId);
                            removePendingQuestion(sessionId, requestId);
                            sendMessage(new AgentSessionPort.SendRequest(sessionId, digest, true));
                            return;
                        }
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

    /**
     * 回答降级文本：原问题卡片的问答对折叠成 "Q: … / A: …" 消息，让模型在丢失原提问
     * 回合后仍能拿到用户决策的语义（而不是一个裸选项列表）。
     */
    private String questionAnswerDigest(String sessionId, String requestId, List<List<String>> answers) {
        List<List<String>> picked = answers == null ? List.of() : answers;
        QuestionRequest pending = null;
        Map<String, QuestionRequest> local = pendingQuestions.get(sessionId);
        if (local != null) {
            pending = local.get(requestId);
        }
        StringBuilder sb = new StringBuilder("[回答已送达（原提问因服务重启丢失，以下为问答内容）]");
        List<QuestionRequest.QuestionPrompt> prompts = pending == null
                ? List.of() : pending.questions();
        for (int i = 0; i < prompts.size(); i++) {
            sb.append("\nQ: ").append(prompts.get(i).question());
            String answer = i < picked.size() && picked.get(i) != null && !picked.get(i).isEmpty()
                    ? String.join("、", picked.get(i))
                    : "（未选择）";
            sb.append("\nA: ").append(answer);
        }
        if (prompts.isEmpty()) {
            // pending 快照也没了（如后端重启后内存表为空）：把原始选项串行送达，不丢语义。
            sb.append("\nA: ");
            for (int i = 0; i < picked.size(); i++) {
                List<String> answer = picked.get(i);
                sb.append(i == 0 ? "" : " | ")
                        .append(answer == null ? "" : String.join("、", answer));
            }
        }
        return sb.toString();
    }

    @Override
    public void rejectQuestion(String sessionId, String requestId) {
        postWithRecovery(sessionId, "/question/" + requestId + "/reject", "{}", "question reject",
                requestId, (resp, healed) -> {
                    if (resp.statusCode() == 404) {
                        if (healed) {
                            // 全新 serve 不持有旧提问：本地关卡片（UI 收到 rejected 事件即收敛）。
                            log.info("opencode", "question.reject-lost", "sessionId", sessionId,
                                    "requestId", requestId);
                            emitChunk(sessionId, new SessionStreamChunk.QuestionRepliedChunk(
                                    sessionId, requestId, true, List.of(), false, clock.now()));
                            return;
                        }
                        // Already rejected elsewhere; treat as resolved.
                        log.info("opencode", "question.already-resolved", "sessionId", sessionId,
                                "requestId", requestId);
                        return;
                    }
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
        // T-107 渲染修复：client_message_id 直通——乐观气泡与 USER 行同 id，历史重载与
        // steer_injected 对账帧都按该 id 锚定；缺省时服务端自配。
        String userMessageId = request.clientMessageId() == null || request.clientMessageId().isBlank()
                ? UUID.randomUUID().toString() : request.clientMessageId();
        sessions.insertMessage(new SessionMessage(userMessageId, session.id(),
                Role.USER, request.message(), List.of(), null, false, clock.now()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        // 普通回合：入队即算运行，且 busy 持续到回合真正结束（opencode 是异步回合：终点是上游
        // session.status=idle，不是 prompt_async 的 HTTP 受理）。失败路径在 catch 里兜底释放。
        // steer（delivery=steer，T-107 插队）：消息实时注入“当前正在运行”的回合，该回合全程只
        // 产生一次 idle 终点——busy 计数与终点消耗都由原回合持有，steer 自身不得计数（否则终点
        // 到达后计数残留、会话永久 busy）。是否真有在跑回合由 runSend 在 POST 前复核：没有则
        // 降级为普通新回合并补计。
        boolean steer = "steer".equals(request.delivery());
        if (!steer) {
            incrementInFlight(session.id());
        }
        executor.submit(() -> runSend(task, session, request.message(), request.attachments(),
                firstTurn, steer ? "steer" : null, userMessageId));
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
        runSend(task, session, message, attachments, firstTurn, null, null);
    }

    private void runSend(GateTask task, Session session, String message,
                         List<AgentSessionPort.Attachment> attachments, boolean firstTurn, String delivery,
                         String userMessageId) {
        // steer（插队）是否骑乘在跑的回合：sendMessage 对 steer 不计数，此处 POST 前复核——
        //  · 在跑（inFlightCounts 含本会话）→ 骑乘：不计数、不动正在接收的回合（不做
        //    superseded 冲刷与缓冲重置，否则会把当前回合误判为“陈旧残留”而掐断）；
        //  · 没在跑（页面 busy 过期/原回合刚释放）→ 降级为普通新回合：补计 busy、按普通回合发。
        // catch 块需要读到该判定，故声明在 try 之外。
        boolean riding = false;
        // 本任务是否持有 busy 计数：非 steer 已由 sendMessage 入队时计数；steer 不计数（计数归
        // 原回合），降级为普通新回合补计后才置真。catch 只释放本任务真正持有的计数——steer 在
        // riding 判定前抛错（ensureServe/配置查询/簿记等阶段）时手面无计数可放，强放会误清
        // 在跑回合持有的 busy。
        boolean holdsBusyCount = !"steer".equals(delivery);
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
            riding = "steer".equals(delivery) && inFlightCounts.containsKey(session.id());
            if ("steer".equals(delivery) && !riding) {
                incrementInFlight(session.id());
                holdsBusyCount = true;
            }
            String effectiveDelivery = riding ? "steer" : null;
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
                    latest.overrideProvider(), latest.overrideModel(), latest.overrideVariant(),
                    effectiveDelivery);
            Upstream up = upstreams.get(session.id());
            if (up != null && !riding) {
                // A previous turn whose stream never reached session.status=idle (serve drop,
                // connection loss, interrupted abort) left buffered content dangling; the reset
                // below would wipe it, so flush it as a degraded reply first and let it survive
                // the next send. riding（steer 插队）时上游回合是活的：绝不清洗。
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
                    // T-107 渲染修复：新回合开始前清掉上个回合残留的待注入 steer——
                    // 其上游公告不会再到来（回合已死/已被 superseded），留着会被下个
                    // 回合中段的真实插队公告误消费（FIFO 串位）。
                    up.pendingSteers.clear();
                    up.turnHasAssistantActivity = false;
                    // V22：请求值兜底——messageBody resolveModel 同口径（实时覆盖优先，回退
                    // AgentConfig 默认 ref）；上游 info 若给出实际值，mergeStep 会覆盖它。
                    ModelRef requested = resolveModel(config,
                            latest.overrideProvider(), latest.overrideModel());
                    up.turnModelProvider = requested == null ? null : requested.providerId();
                    up.turnModelId = requested == null ? null : requested.modelId();
                    // V23：推理强度落库值 = 本次 prompt 实际钉住的 variant（messageBody
                    // 同源 latest.overrideVariant，空白即未选档位，落 null）。
                    String reqVariant = latest.overrideVariant();
                    up.turnVariant = reqVariant == null || reqVariant.isBlank() ? null : reqVariant.trim();
                }
            }
            // T-107 渲染修复：骑乘 steer 在 POST 前登记待注入条目——上游的 user 公告可能
            // 先于 HTTP 响应返回（reader 是独立线程），受理后才登记会错过注入窗口。
            Upstream steerTarget = riding ? upstreams.get(session.id()) : null;
            if (steerTarget != null && userMessageId != null) {
                steerTarget.queueSteer(userMessageId, message);
            }
            HttpResponse<String> resp = post("http://127.0.0.1:" + port + "/session/"
                    + session.cliSessionId() + "/prompt_async", body);
            if (resp.statusCode() / 100 != 2) {
                if (steerTarget != null) {
                    steerTarget.removeSteer(userMessageId);
                }
                log.error("opencode", "prompt_async.rejected",
                        "sessionId", session.id(), "status", resp.statusCode(),
                        "bodySnippet", resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "opencode prompt_async failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            log.info("opencode", "prompt_async.accepted", "sessionId", session.id(),
                    "cliSessionId", session.cliSessionId(), "chars", message.length(),
                    "delivery", effectiveDelivery == null ? "default" : effectiveDelivery);
            // 受理即进入“等待终点释放”状态：busy 只能被本回合之后的 idle/error 终点消耗一次。
            // riding 时该标记由原回合持有（Set 幂等），idle 终点到达后由原回合的计数消耗。
            acceptedSinceRelease.add(session.id());
            // The turn itself runs asynchronously; token/tool/done chunks arrive on the upstream
            // reader and the final assistant message is persisted from its completion snapshot.
            tasks.update(success(task, "{\"accepted\":true}"));
        } catch (Throwable e) {
            // T-107 渲染修复：POST 阶段（含受理前）抛错时撤回已登记的待注入 steer，
            // 其上游公告永远不会到来，残留条目会串到下个回合中段的真实公告上。
            if (riding && userMessageId != null) {
                Upstream failed = upstreams.get(session.id());
                if (failed != null) {
                    failed.removeSteer(userMessageId);
                }
            }
            // Persist the failure so a page reload still shows why the turn died, mirroring the
            // claude adapter's ERROR-message behaviour.
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                    Role.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    List.of(), null, true, clock.now()));
            // steer 在 riding 判定前抛错（holdsBusyCount 仍为 false）：若会话确有在跑回合
            // （计数由原回合持有），语义等同骑乘失败——既不能释放（会误清在跑回合的 busy、
            // 拆掉其受理标记），也不能发 ErrorChunk（会掐断在跑回合的实时视图）。
            boolean rideFailed = riding || ("steer".equals(delivery) && !holdsBusyCount
                    && inFlightCounts.containsKey(session.id()));
            if (rideFailed) {
                // 骑乘失败（steer 被拒/上游不可达）：原回合仍可能继续产生 idle 终点，busy 计数
                // 归原回合所有，绝不能在此无条件释放——否则在跑的回合被提前标记为空闲。
                // 同时不补发 ErrorChunk/补偿错误：回合没死，正在流的视图不能被一个失败帧掐断，
                // 失败已以 ERROR 行落库，刷新历史可见。
                log.warn("opencode", "steer.ride-failed", "sessionId", session.id(),
                        "error", e.getClass().getSimpleName());
            } else {
                emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "INTERNAL_ERROR", e.getMessage(), clock.now()));
                // 浏览器的 SSE 多半还没挂上（POST /messages 刚返回）：记入 3 秒补偿窗口，
                // attachListener 迟到即补发，否则 UI 转圈到看门狗超时。
                recentErrors.put(session.id(), new RecentError(System.currentTimeMillis(),
                        "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                // 回合根本没被受理：不会有 idle 事件到来，立即释放 busy 计数。必须先于任务簿记——
                // 簿记失败（fence 冲突等）绝不允许把 busy 泄漏成常驻 1。只释放本任务自己持有的
                // 计数（非 steer 入队即计 / steer 降级补计）；steer 未计数时释放会误清在跑回合。
                if (holdsBusyCount) {
                    releaseBusyUnconditionally(session.id());
                }
            }
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
     * reasoning-effort selection (same field the composer sends).
     */
    static String messageBody(AgentConfig config, String message,
                              String overrideProvider, String overrideModel, String overrideVariant) {
        return messageBody(config, message, List.of(), overrideProvider, overrideModel, overrideVariant);
    }

    /**
     * Full shape: image attachments ride along as {@code file} parts after the text part — the
     * same contract the composer uses ({@code {type:"file", mime, filename?, url}} with
     * a {@code data:} URL payload).
     */
    static String messageBody(AgentConfig config, String message,
                              List<AgentSessionPort.Attachment> attachments,
                              String overrideProvider, String overrideModel, String overrideVariant) {
        return messageBody(config, message, attachments, overrideProvider, overrideModel, overrideVariant, null);
    }

    static String messageBody(AgentConfig config, String message,
                              List<AgentSessionPort.Attachment> attachments,
                              String overrideProvider, String overrideModel, String overrideVariant,
                              String delivery) {
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
        if (delivery != null && !delivery.isBlank()) {
            body.append(",\"delivery\":\"").append(escapeJson(delivery.trim())).append('"');
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
        // 每个思考/文本 part 的累计转发正文（key: partId）：真 delta（message.part.delta）
        // 与节流快照（message.part.updated）都以此为对齐基准——delta 先做最长后缀重叠
        // 剔除（快照覆盖过的文本会在后续 delta 里重复出现），快照只转发累计之外的
        // 新尾部。增量事件 reducer 同款语义的后端版。
        final Map<String, StringBuilder> partText = new ConcurrentHashMap<>();
        // partId → type（text/reasoning/tool）：message.part.delta 的 field 是被追加的
        // 属性名（reasoning part 的正文字段也叫 text），思考/正文分流必须按 part.type
        // ——part.updated 建档记录，delta 消费时查表。
        final Map<String, String> partTypes = new ConcurrentHashMap<>();
        // Final snapshot text per assistant message: messageId -> (partId -> latest full text).
        // Replace-not-append keeps persistence idempotent: opencode re-announces a finished
        // step's text under a fresh part id, and the former append model doubled exactly that
        // content in persisted history (visible after switching sessions).
        final Map<String, LinkedHashMap<String, String>> messageParts = new ConcurrentHashMap<>();
        // Tool calls per assistant message keyed by upstream callID; upserted as state
        // transitions stream in and drained into the persisted ASSISTANT row on completion,
        // so reloading a session re-renders 工具调用 instead of degrading to plain text.
        final Map<String, LinkedHashMap<String, ToolCallState>> toolsByMessage = new ConcurrentHashMap<>();
        // 时间线草稿：messageId -> (槽键 -> 槽)。槽键前缀定序（t: 文本 / r: 思考 /
        // 工具 callID），LinkedHashMap 保到达序；text 是快照 replace、thinking 增量 append、
        // tool 原位更新终态。步完成（mergeStepIntoTurn）按序 drain 成 TurnPart 进 turnParts。
        final Map<String, LinkedHashMap<String, PartSlot>> partsByMessage = new ConcurrentHashMap<>();
        final java.util.Set<String> mergedMessages = ConcurrentHashMap.newKeySet();
        // Roles are announced via message.updated before a message's parts stream in; user-message
        // parts echo the prompt verbatim and must never surface as assistant content chunks.
        final java.util.Set<String> assistantMessages = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> userMessages = ConcurrentHashMap.newKeySet();
        // Turn grouping: an agentic turn spans MANY assistant messages
        // (one per step). Everything accumulates here and persists as ONE reply when the
        // turn goes idle — instead of one noisy bubble per intermediate CoT step.
        final StringBuilder turnText = new StringBuilder();
        final List<TurnTool> turnTools = java.util.Collections.synchronizedList(new ArrayList<>());
        // 时间线分段：reasoning/text/tool 按真实到达序累积，落库后历史可整段
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
        // 连续重连失败计数：成功建流（connected）即清零；达到 UPSTREAM_HEAL_AFTER_FAILED_CONNECTS
        // 视为 serve 进程死亡，触发 healDeadServe（否则每 2s 一条 dropped WARN 死循环，回合
        // 终点与 busy 释放随进程一起蒸发，会话永久僵死）。
        final AtomicInteger failedConnects = new AtomicInteger();
        // Turn bookkeeping: if a turn ends (idle) without any assistant completion, the buffered
        // session.error is persisted as an ERROR row so failures survive page reloads.
        volatile boolean assistantPersistedSinceSend;
        volatile String pendingErrorName;
        volatile String pendingErrorMessage;
        // V22 逐消息模型标注：modelsByMessage 是上游 message.updated 的 info 里按消息抽到的
        // {providerID, modelID}（实际模型的权威来源）；turnModel* 是本回合随行落库的值——
        // mergeStep 以实际值覆盖（切换只在下一回合生效，回合内恒定，后写覆盖先写），发送
        // 线程在重置块预置请求值兜底（上游 info 缺字段时 flushTurn 仍有值可落）。
        final Map<String, String[]> modelsByMessage = new ConcurrentHashMap<>();
        String turnModelProvider;
        String turnModelId;
        // V23 逐消息推理强度：上游不回传实际生效的 effort，落库值即发送端钉住的请求
        // variant（prompt body 同字段），发送线程在重置块预置，flushTurn 随行落库。
        String turnVariant;
        // 序列化回合缓冲的全部读写者：reader 线程（step 合并）、send 线程（superseded 重置）、
        // 停机/错误路径（flush 落库）。turnText 是普通 StringBuilder，跨线程读写必须加锁。
        // flush 的 DB I/O 在锁外执行，锁只覆盖缓冲快照与清空。
        final Object turnLock = new Object();
        // T-107 渲染修复：待注入的 steer（插队）条目。runSend 骑乘受理前登记（FIFO，同一会话
        // 内公告顺序与投递顺序一致）；reader 在回合中段看到 user 公告时弹出队首——位置由
        // 时间线承载，注入点即 turnParts 的当前末尾，随 flushTurn 一并落库为 steer 分段。
        // 公告缺失（serve 崩溃/回合中断）时条目在 TTL 窗口后被后续公告清掉，绝不串位。
        final java.util.Deque<PendingSteer> pendingSteers = new java.util.concurrent.ConcurrentLinkedDeque<>();
        // 本回合自上一个终点以来是否已有 assistant 活动：区分“新回合开头的 user 回声”
        // （不注入）与“回合中段的 steer 公告”（注入）。
        volatile boolean turnHasAssistantActivity;

        /** 一次待注入的 steer：messageId 为 gate USER 行 id（client_message_id 对账锚点）。 */
        record PendingSteer(String messageId, String text, long atMs) {
        }

        /** steer 待注入条目的有效窗口：上游公告迟迟不来（回合夭折）时条目过期作废。 */
        private static final long STEER_TTL_MS = 60_000L;

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

        /**
         * V21 任务清单快照：todowrite 到达即 journal（不等整回合 idle 落库）。opencode 每次
         * part 更新都重发完整 input 快照，幂等覆盖、last-write-wins；空数组=显式清空也落。
         * journal 失败只记日志——快照是派生数据，绝不影响回合进行。
         */
        private void journalTodoSnapshot(String name, String inputJson) {
            if (!TodoSnapshots.isWriteTool(name)) {
                return;
            }
            String canonical = TodoSnapshots.canonicalJson(inputJson);
            if (canonical == null) {
                return;
            }
            try {
                sessions.upsertTodos(sessionId, canonical);
            } catch (Exception e) {
                log.warn("opencode", "todo.journal-failed", "sessionId", sessionId,
                        "error", e.getClass().getSimpleName());
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
                    failedConnects.set(0);
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
                    int failures = failedConnects.incrementAndGet();
                    log.warn("opencode", "upstream.dropped", "sessionId", sessionId,
                            "port", port, "error", e.getClass().getSimpleName(),
                            "failedConnects", failures);
                    // serve 进程死亡不会自愈：连续失败达到阈值即触发会话自愈（冲刷在途回合、
                    // 释放 busy、补发 done、清理端口），然后终止本 reader；下次使用由
                    // ensureServe 懒复活（opencode 侧会话数据完好）。
                    if (!stopped && failures >= UPSTREAM_HEAL_AFTER_FAILED_CONNECTS
                            && healDeadServe(sessionId, port)) {
                        return;
                    }
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
                case "message.part.delta" -> handlePartDelta(props);
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
            // 建档 part 类型：message.part.delta 的 field 是属性名不是类型（reasoning part
            // 的正文字段也叫 text），delta 消费时按此表分流思考/正文。
            if (partId != null && partType != null && !partType.isBlank()) {
                partTypes.put(partId, partType);
            }

            if (messageId != null && userMessages.contains(messageId)) {
                return;
            }

            if ("text".equals(partType)) {
                String full = str(part.get("text"));
                if (full == null) {
                    return;
                }
                String chunk = forwardNewTail(partId, full);
                if (!full.isEmpty()) {
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
                String chunk = forwardNewTail(partId, full);
                if (!chunk.isEmpty()) {
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
                journalTodoSnapshot(toolName, inputJson);
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

        /**
         * 真·流式增量（上游原生事件，~60 次/秒）。此前我们只消费节流快照
         * part.updated，流式体感"一顿一顿"——快照是 opencode 内部按秒级合并后的广播，
         * token 级内容全在这里。
         *
         * <p>关键语义：{@code field} 是被追加的 part 属性名——reasoning part 的正文字段
         * 恰好也叫 "text"，绝不能按 field 分类！事件 reducer 先按
         * messageID/partID 定位 part（part.type 即思考/正文），再写 field 指定的属性。
         * 此前按 field=="reasoning" 分流，reasoning 的 text delta 全被误当正文转发——
         * 思考独白整段漏进正文流、思考行只剩快照尾巴（时灵时不灵取决于快照与 delta
         * 的到达竞态），部分回合两者并存（同内容双渲染）。
         *
         * <p>delta 与快照共用 {@link #partText} 累计正文对齐 + 最长后缀重叠剔除
         * （appendNonOverlappingDelta 同款）；分类锚点是 {@link #partTypes} 注册表
         * （part.updated 建档记录的 type）。工具 output 等其余字段仍由 part.updated 驱动。
         */
        @SuppressWarnings("unchecked")
        private void handlePartDelta(Map<String, Object> props) {
            String messageId = str(props.get("messageID"));
            String partId = str(props.get("partID"));
            String field = str(props.get("field"));
            String delta = str(props.get("delta"));
            // 只接正文字段（reasoning/text 两类 part 的正文属性都叫 text）；
            // 工具 output 等其余字段不在此驱动。
            if (partId == null || delta == null || delta.isEmpty() || !"text".equals(field)) {
                return;
            }
            String partType = partTypes.get(partId);
            if (partType == null) {
                // delta 竞速领先于 part.updated：先按 reasoning 建档占位，后续
                // part.updated 到达同 key 覆盖为真实类型（opencode 事件序上思考
                // part 先建，实践几乎总命中）。
                partType = "reasoning";
                partTypes.put(partId, partType);
            }
            if (messageId != null && userMessages.contains(messageId)) {
                return;
            }
            Instant now = clock.now();
            String chunk = appendAligned(partText.computeIfAbsent(partId, k -> new StringBuilder()), delta);
            if (chunk.isEmpty()) {
                return;
            }
            if ("reasoning".equals(partType)) {
                emitChunk(sessionId, new SessionStreamChunk.ThinkingChunk(sessionId, chunk, now));
                if (messageId != null) {
                    draft(messageId, "r:" + partId, "thinking").text.append(chunk);
                }
            } else {
                emitChunk(sessionId, new SessionStreamChunk.ContentChunk(sessionId, chunk, now));
                if (messageId != null) {
                    // 时间线文本草稿按累计正文对齐（与快照分支同一 replace-not-append 语义）
                    draft(messageId, "t:" + partId, "text").text =
                            new StringBuilder(partText.get(partId).toString());
                }
            }
        }

        /**
         * 快照对齐转发：返回 {@code full} 相对已累计正文的新增尾部，并把累计正文推进到
         * {@code full}。快照可能回退（opencode 重发已完成 step 的文本时是新 partId，正常
         * 不会同 id 回退；同 id 长度回退按重叠剔除处理，绝不清零——delta 流不能被快照重置）。
         */
        private String forwardNewTail(String partId, String full) {
            StringBuilder acc = partText.computeIfAbsent(partId, k -> new StringBuilder());
            return appendAligned(acc, full);
        }

        /**
         * 把 {@code incoming} 对齐进 {@code acc}，返回真正新增的尾部：incoming 以 acc 结尾
         * 或与 acc 有最长后缀重叠时只追加未重叠部分，否则整段追加。这是增量事件
         * reducer 的 appendNonOverlappingDelta 语义——快照/双流并存时两路内容不重复。
         */
        private static String appendAligned(StringBuilder acc, String incoming) {
            if (incoming.isEmpty()) {
                return "";
            }
            String existing = acc.toString();
            if (incoming.equals(existing) || incoming.startsWith(existing)) {
                // incoming 覆盖已累计正文（典型：节流快照）：只追加超出部分
                String tail = incoming.substring(existing.length());
                acc.append(tail);
                return tail;
            }
            if (existing.endsWith(incoming)) {
                // delta 已被某次快照覆盖过：纯重复，无新增
                return "";
            }
            int maxOverlap = Math.min(existing.length(), incoming.length());
            for (int overlap = maxOverlap; overlap > 0; overlap--) {
                if (existing.endsWith(incoming.substring(0, overlap))) {
                    String tail = incoming.substring(overlap);
                    acc.append(tail);
                    return tail;
                }
            }
            acc.append(incoming);
            return incoming;
        }

        void queueSteer(String messageId, String text) {
            pendingSteers.addLast(new PendingSteer(messageId, text, System.currentTimeMillis()));
        }

        void removeSteer(String messageId) {
            pendingSteers.removeIf(p -> p.messageId().equals(messageId));
        }

        /**
         * 回合中段的 user 公告 = steer（插队）回声：弹出队首待注入条目，把 steer 分段
         * 插进回合时间线（当前位置），并补发 steer_injected 对账帧（乐观气泡按 id 迁入
         * 时间线）。新回合开头的 user 回声不满足“已有 assistant 活动”条件，直接跳过。
         */
        private void injectPendingSteer() {
            long now = System.currentTimeMillis();
            PendingSteer head;
            // 过期条目（公告永远没来的死回合残留）就地作废，避免串到无关公告上。
            while ((head = pendingSteers.peekFirst()) != null && now - head.atMs() > STEER_TTL_MS) {
                pendingSteers.pollFirst();
                log.warn("opencode", "steer.expired", "sessionId", sessionId,
                        "messageId", head.messageId());
            }
            if (pendingSteers.isEmpty() || !turnHasAssistantActivity) {
                return;
            }
            PendingSteer ps = pendingSteers.pollFirst();
            synchronized (turnLock) {
                turnParts.add(TurnPart.steer(ps.messageId(), ps.text()));
            }
            emitChunk(sessionId, new SessionStreamChunk.SteerInjectedChunk(sessionId,
                    ps.messageId(), ps.text(), clock.now()));
            log.info("opencode", "steer.injected", "sessionId", sessionId,
                    "messageId", ps.messageId(), "chars", ps.text().length());
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
                // 本回合已有 assistant 活动：此后的 user 公告即插队回声（T-107 渲染修复）。
                turnHasAssistantActivity = true;
                // V22：上游 info 自带本消息实际使用的模型（providerID/modelID），权威来源——
                // 抽出来供 flushTurn 随行落库（map 里多存一份也不碍事，key 随消息清理）。
                if (messageId != null) {
                    assistantMessages.add(messageId);
                    String provider = str(info.get("providerID"));
                    String model = str(info.get("modelID"));
                    if (provider != null || model != null) {
                        modelsByMessage.put(messageId, new String[]{provider, model});
                    }
                }
            } else if ("user".equals(role)) {
                if (messageId != null) {
                    // T-107 渲染修复：回合中段的 user 公告是 steer（插队）回声——注入
                    // 时间线并补发对账帧。新回合开头的 echo 因无 assistant 活动而跳过。
                    // 同一消息的 message.updated 可能推送多次（创建/完成各一帧）：只有
                    // 首帧触发注入——否则第二帧会弹出队列中下一条无关的待注入条目，
                    // 多条插队消息串位/提前消费。
                    if (userMessages.add(messageId)) {
                        injectPendingSteer();
                    }
                    // A user part that raced ahead of this announcement buffered itself; drop it.
                    messageParts.remove(messageId);
                    toolsByMessage.remove(messageId);
                    modelsByMessage.remove(messageId);
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
                // V22：该消息的实际模型并入回合值（后写覆盖先写；缺字段的消息不覆盖）。
                String[] stepModel = modelsByMessage.remove(messageId);
                if (stepModel != null && stepModel[1] != null && !stepModel[1].isBlank()) {
                    turnModelProvider = stepModel[0];
                    turnModelId = stepModel[1];
                }
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
         * assistant reply — the turn grouping of the idle path, reused here
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
            final String modelProvider;
            final String modelId;
            final String variant;
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
                // V22：回合模型随行落库——实际值（mergeStep 覆盖）优先，缺省即发送线程
                // 预置的请求值兜底。V23：推理强度同批快照与清空。
                modelProvider = turnModelProvider;
                modelId = turnModelId;
                variant = turnVariant;
                turnModelProvider = null;
                turnModelId = null;
                turnVariant = null;
                turnText.setLength(0);
                turnUsage = null;
                turnHasNewContent = false;
                // T-107 渲染修复：回合终点即时间线收口——重置“回合中段”判定，并丢弃
                // 仍未被公告消费的 steer 条目（回合已死，公告不会再按旧位置到来）。
                turnHasAssistantActivity = false;
                pendingSteers.clear();
                assistantPersistedSinceSend = true;
            }
            sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                    sessionId, Role.ASSISTANT, content, tools, usage, degraded, clock.now(), parts,
                    modelProvider, modelId, variant));
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
                // usage) as ONE assistant reply — turn grouping.
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
                // Last-Event-ID.
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
                    // V22：上游 info 的实际模型（权威来源）；缺字段时保留 turn 里已有的值。
                    String provider = str(info.get("providerID"));
                    String model = str(info.get("modelID"));
                    if (model != null && !model.isBlank()) {
                        turn.modelProvider = provider;
                        turn.modelId = model;
                    }
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
                List.copyOf(turn.tools), turn.usage, true, clock.now(), List.of(),
                turn.modelProvider, turn.modelId, null));
        turn.text.setLength(0);
        turn.tools.clear();
        turn.usage = null;
        turn.lastText = null;
        turn.modelProvider = null;
        turn.modelId = null;
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
        // V22：本回合实际模型（上游 info 逐消息覆盖），随 ASSISTANT 行落库。
        String modelProvider;
        String modelId;

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
     * 端口择位：从分配器取一个端口并确保它真的可用。两道闸——① 有人应答 /health 说明被占，
     * 先尝试孤儿自愈（收割占着该端口的 opencode serve，上次运行被强杀时必然发生）；② 无人
     * 应答也不等于能用，还要真去 bind 一次（见 {@link #bindable}）。两道都没过就释放该端口，
     * 按 2^n 跨步换下一个候选。
     */
    private int acquireUsablePort() {
        int skip = 1;
        int attempts = 0;
        while (true) {
            int port = ports.allocate();
            if (opencodeExecutable == null || opencodeExecutable.isBlank()) {
                return port; // 无 CLI 可执行体：不 spawn，端口仅作占位
            }
            String refusal = null;
            if (probePortOccupied(port)) {
                healStaleServe(port);
                if (probePortOccupied(port)) {
                    refusal = "start.port-busy-skip"; // 外来进程占用
                }
            }
            if (refusal == null && !bindableWithRetry(port)) {
                refusal = "start.port-unbindable-skip";
            }
            if (refusal == null) {
                return port;
            }
            // 坏端口：释放并按 1,2,4,8… 跨步跳过当前游标邻域。allocate 本身已消耗当前格，
            // 跨 n 格只需再推 n-1 格。
            log.warn("opencode", refusal, "port", port, "skip", skip);
            ports.release(port);
            ports.skip(skip - 1);
            skip = Math.min(skip * 2, 4096);
            if (++attempts >= 32) {
                throw portBusy(port, "port range exhausted after " + attempts + " skipping attempts");
            }
        }
    }

    /**
     * 孤儿收割后 socket 关闭到可重新 bind 有毫秒级延迟，连试几拍再判死，免得把刚腾出来的
     * 好端口当坏端口跳过。
     */
    private boolean bindableWithRetry(int port) {
        for (int i = 0; i < 5; i++) {
            if (bindable(port)) {
                return true;
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 真去 bind 一次再立刻放开：这是唯一能识别「内核排除段」的手段。winnat/Hyper-V 开机自动
     * 划走一段端口（netsh 删不掉），段内端口无人监听、netstat 看不见、{@code /health} 探活
     * 一无所获，但任何进程 bind 都被内核拒（Windows WSAEACCES/10013）——serve 于是启动即退出
     * （"exited before becoming healthy (exit 1)"），表象酷似端口被别人占用。
     */
    static boolean bindable(int port) {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.setReuseAddress(false); // 严格探法：连地址复用都不许，能 listen 才算真可用
            probe.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
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
            // A gate backend launched from inside an OpenCode-managed shell inherits
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
                                + " (exit " + proc.exitValue() + ")" + serveDeathTail(port));
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

    /** serve 遗言最多回看多少字节、取几行、拼多长——错误帧是单行文本，够指认死因即可。 */
    private static final int SERVE_LOG_TAIL_BYTES = 8192;
    private static final int SERVE_LOG_TAIL_LINES = 3;
    private static final int SERVE_LOG_TAIL_CHARS = 400;
    private static final java.util.regex.Pattern ANSI_ESCAPE =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    /**
     * 死因尾注：进程没起来就退出时，把 serve 遗言文件的最后几行附在异常里。此前错误只有
     * 「exited before becoming healthy on port N (exit 1)」——端口二字把排查方向带向端口占用，
     * 而真实原因（配置校验失败、Node OOM、崩溃栈）全躺在遗言文件里无人看见。
     */
    private String serveDeathTail(int port) {
        String tail = tailOfServeLog(serveLogPath(port));
        return tail.isEmpty() ? "" : " — serve said: " + tail;
    }

    /**
     * 读遗言文件末尾若干 KB，剥掉 ANSI 色码，取最后几行非空内容单行拼接（错误帧是单行文本，
     * 换行会在 UI 里散开）。文件缺失/读不动一律返回空串——诊断信息永远不该盖过原始错误。
     */
    static String tailOfServeLog(Path log) {
        String body;
        try {
            if (!Files.exists(log)) {
                return "";
            }
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log.toFile(), "r")) {
                long size = raf.length();
                int len = (int) Math.min(size, SERVE_LOG_TAIL_BYTES);
                byte[] buf = new byte[len];
                raf.seek(size - len);
                raf.readFully(buf);
                String text = new String(buf, StandardCharsets.UTF_8);
                // 截断窗口时首行是半行（还可能带半个多字节字符），整行丢弃
                if (len < size) {
                    int nl = text.indexOf('\n');
                    text = nl >= 0 ? text.substring(nl + 1) : "";
                }
                body = text;
            }
        } catch (Exception e) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String raw : ANSI_ESCAPE.matcher(body).replaceAll("").split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        String joined = String.join(" | ",
                kept.subList(Math.max(0, kept.size() - SERVE_LOG_TAIL_LINES), kept.size()));
        return joined.length() <= SERVE_LOG_TAIL_CHARS
                ? joined
                : joined.substring(0, SERVE_LOG_TAIL_CHARS) + "…";
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
