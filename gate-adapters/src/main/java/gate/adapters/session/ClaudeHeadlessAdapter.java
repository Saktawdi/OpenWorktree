package gate.adapters.session;

import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.domain.session.AgentConfig;
import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.SessionUsage;
import gate.domain.session.TurnPart;
import gate.domain.session.PermissionRequest;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.domain.ticket.Ticket;
import gate.ports.store.AgentConfigRepository;
import gate.ports.session.AgentSessionPort;
import gate.ports.infra.Clock;
import gate.ports.infra.ProcessRunner;
import gate.ports.store.ProjectRepository;
import gate.ports.store.SessionRepository;
import gate.ports.store.TicketStageChangeRepository;
import gate.ports.task.TaskRegistry;
import gate.ports.infra.TicketLockManager;
import gate.ports.store.TicketRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Claude headless adapter (执行文档-后端-web §5.3.2, ADR-12): per-message {@code claude -p} spawn
 * with the prompt as a positional argument, {@code stream-json} output and {@code --resume} for
 * continuation.
 *
 * <p>This is the first session adapter (D4 ②). The port returns structured records; transport or
 * parse failures become an ERROR {@link SessionMessage} instead of escaping the port where possible.
 */
public final class ClaudeHeadlessAdapter implements AgentSessionPort {

    private final ProcessRunner processRunner;
    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessions;
    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final TicketStageChangeRepository restarts;
    private final TaskRegistry tasks;
    private final TicketLockManager ticketLocks;
    private final Clock clock;
    private final String claudeExecutable;
    private final List<String> claudePrefix;
    /**
     * gate.toml location for MCP provisioning. Null = provisioning disabled (tests); the
     * mcp-config.json is then not written at all, as before this capability existed.
     */
    private final Path gateToml;
    /** Null = auto base sync disabled (legacy wirings/tests); set, every session start re-syncs the clone base. */
    private final gate.ports.git.BaseSynchronizer baseSynchronizer;
    private final ExecutorService executor;
    private final Map<String, Set<Consumer<SessionStreamChunk>>> listeners = new ConcurrentHashMap<>();
    // 有进行中回合的 session id 快照（入队即算运行，排队等待也算），用于顶栏 busy 统计
    // 同一 session 可能连续 send，两次都在 executor 队列中等待；用引用计数保证全部完成后才移出
    private final Map<String, AtomicInteger> inFlightCounts = new ConcurrentHashMap<>();

    public ClaudeHeadlessAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 String claudeExecutable) {
        this(processRunner, agentConfigs, sessions, tickets, tasks, ticketLocks, clock, claudeExecutable, List.of());
    }

    /**
     * Test/advanced constructor allowing a command prefix (e.g. {@code ["/c", "fake-claude.cmd"]}
     * on Windows). The prefix is inserted right after the executable.
     */
    public ClaudeHeadlessAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 String claudeExecutable,
                                 List<String> claudePrefix) {
        this(processRunner, agentConfigs, sessions, tickets, null, null, tasks, ticketLocks, clock,
                claudeExecutable, claudePrefix, null);
    }

    /** Full constructor: {@code projects} is optional (null skips the 项目 section of the injected context). */
    public ClaudeHeadlessAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TicketStageChangeRepository restarts,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 String claudeExecutable,
                                 List<String> claudePrefix,
                                 Path gateToml) {
        this(processRunner, agentConfigs, sessions, tickets, projects, restarts, tasks, ticketLocks,
                clock, claudeExecutable, claudePrefix, gateToml, null);
    }

    /** Fullest constructor: {@code baseSynchronizer} re-syncs the clone base on every session start (T-118). */
    public ClaudeHeadlessAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TicketStageChangeRepository restarts,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 String claudeExecutable,
                                 List<String> claudePrefix,
                                 Path gateToml,
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
        this.claudeExecutable = claudeExecutable;
        this.claudePrefix = claudePrefix == null ? List.of() : List.copyOf(claudePrefix);
        this.gateToml = gateToml;
        this.baseSynchronizer = baseSynchronizer;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claude-session");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public AutoCloseable attachListener(String sessionId, Consumer<SessionStreamChunk> listener) {
        listeners.computeIfAbsent(sessionId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(listener);
        return () -> {
            Set<Consumer<SessionStreamChunk>> set = listeners.get(sessionId);
            if (set != null) {
                set.remove(listener);
            }
        };
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
    public Session start(StartRequest request) {
        try (AutoCloseable ignored = ticketLocks.acquire(request.ticketNo())) {
            // V19 快速模式: never git-touch the project workspace behind a super ticket (see opencode).
            if (!isSuperTicket(request.ticketNo())) {
                syncBaseQuietly(request.ticketNo());
            }
            return startLocked(request);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "session start failed", e);
        }
    }

    /**
     * T-118: re-sync the clone base before the agent touches the clone, so a ticket that sat in
     * the queue while main moved does not accumulate integration debt. Best-effort by design: a
     * failed or skipped sync must not block the session — a stale base surfaces loudly enough at
     * presubmit (BASE_STALE), and the manual sync endpoint can replay a dirty worktree, which the
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
        } catch (Exception ignored) {
            // see above — session start proceeds; presubmit will refuse a stale base
        }
    }

    private Session startLocked(StartRequest request) {
        AgentConfig config = agentConfigs.find(request.agentConfigId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such agent config: " + request.agentConfigId()));
        Instant now = clock.now();
        String sessionId = UUID.randomUUID().toString();
        Path clone = Path.of(request.clonePath());
        Path contextDir = clone.resolve(".git").resolve("gate-context");
        Path contextFile = contextDir.resolve("CLAUDE.md");
        Path mcpConfig = contextDir.resolve("mcp-config.json");
        writeContext(contextFile, request.ticketNo(), request.targetRef(), config);
        writeMcpConfig(mcpConfig, request.env());

        PlannedArgv argv = buildArgv(config, contextFile, mcpConfig, null, request.initialPrompt());
        Session session = new Session(
                sessionId, request.ticketNo(), config.id(), AgentCli.CLAUDE, SessionStatus.ACTIVE,
                null, request.clonePath(), -1, now, null, SessionUsage.EMPTY, null, false);
        sessions.insert(session);

        // Empty initial_prompt = create an idle session: do not spawn claude, do not record a
        // (meaningless empty) user message. The workbench sends the first real message later
        // via POST /messages (sessionCreate sends "" when initial_prompt is absent).
        if (request.initialPrompt() == null || request.initialPrompt().isBlank()) {
            emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, sessionId, now));
            return sessions.find(sessionId).orElse(session);
        }

        insertUserMessage(sessionId, request.initialPrompt(), now);

        // 首个回合（initial_prompt 非空即开跑）同样计入 busy：会话创建即运行，
        // 此前不在统计内，顶栏在该回合运行期间会错误显示 0。
        incrementInFlight(sessionId);
        try {
            StreamEcho echo = new StreamEcho(sessionId);
            ProcessRunner.ProcRun run = processRunner.runStreaming(argv.argv(), clone, request.env(), Duration.ofMinutes(10),
                    echo::line, null);

            ParsedOutput parsed = parseStream(run.stdout());
            Session withUsage = session;
            if (parsed.sessionId() != null) {
                withUsage = withUsage.withCliSessionId(parsed.sessionId());
            }
            if (parsed.errorText() != null && !parsed.errorText().isBlank()) {
                // result.is_error：claude 以 exit 0 结束但回合失败（如网关 4xx），按错误落库。
                insertErrorMessage(sessionId, parsed.errorText(), now);
                emitChunk(sessionId, new SessionStreamChunk.ErrorChunk(sessionId, "PROCESS_ERROR",
                        parsed.errorText(), now));
            } else {
                List<String> texts = parsed.assistantTexts();
                for (int i = 0; i < texts.size(); i++) {
                    // 一轮 run 只落一条 assistant：时间线（parts）与 usage 都挂最后一条，
                    // 前面多条仅出现在多回合 run（rounds within one process）。
                    insertAssistantMessage(sessionId, texts.get(i),
                            i == texts.size() - 1 ? parsed.parts() : List.of(),
                            i == texts.size() - 1 ? parsed.usage() : null, parsed.degraded(), now,
                            i == texts.size() - 1 ? actualModelProvider(parsed, argv.requestProvider()) : null,
                            i == texts.size() - 1 ? actualModelId(parsed, argv.requestModelId()) : null);
                }
            }
            if (parsed.usage() != null) {
                withUsage = withUsage.withCumulativeUsage(parsed.usage());
                sessions.update(withUsage);
                writeback(withUsage);
                emitChunk(sessionId, new SessionStreamChunk.UsageChunk(sessionId, parsed.usage(), now));
            }
            if (!run.ok() && parsed.sessionId() == null) {
                insertErrorMessage(sessionId, run.stderrFirstLine(), now);
                sessions.update(withUsage.withStatus(SessionStatus.ABORTED).withFinishedAt(now));
                emitChunk(sessionId, new SessionStreamChunk.ErrorChunk(sessionId, "PROCESS_ERROR", run.stderrFirstLine(), now));
            } else {
                emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, sessionId, now));
            }
            return sessions.find(sessionId).orElse(withUsage);
        } finally {
            // 覆盖正常完成、ErrorChunk、异常、中断所有出口，不得依赖是否存在 SSE 监听者
            decrementInFlight(sessionId);
        }
    }

    @Override
    public String sendMessage(SendRequest request) {
        Session session = sessions.find(request.sessionId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + request.sessionId()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        // 入队即算运行：登记发生在提交 executor 之前，排队等待也算运行中；同一 session 多次 send 用引用计数
        incrementInFlight(session.id());
        executor.submit(() -> runSend(task, session, request.message()));
        return task.id();
    }

    @Override
    public void abort(String sessionId) {
        // 兜底清除：abort 即视为回合终止，立即移出 busy 集合，避免 runSend 仍在阻塞时顶栏持续显示运行中
        inFlightCounts.remove(sessionId);
        sessions.find(sessionId).ifPresent(s -> {
            Session aborted = s.withStatus(SessionStatus.ABORTED).withFinishedAt(clock.now());
            sessions.update(aborted);
            writeback(aborted);
            emitChunk(sessionId, new SessionStreamChunk.ErrorChunk(sessionId, "ABORTED", "Session aborted by user", clock.now()));
        });
    }

    @Override
    public List<SessionMessage> getHistory(String sessionId) {
        return sessions.findMessages(sessionId);
    }

    @Override
    public Stream<SessionEvent> streamEvents(String sessionId) {
        List<SessionEvent> events = new ArrayList<>();
        for (SessionMessage m : sessions.findMessages(sessionId)) {
            events.add(new SessionEvent(sessionId, m, "message"));
        }
        return events.stream();
    }

    @Override
    public void respondPermission(String sessionId, String permissionId, String response) {
        // The web layer intercepts non-opencode sessions before dispatching; this is a hard
        // contract violation guard rather than a reachable path.
        throw new UnsupportedOperationException(
                "permission asks are only supported for opencode sessions");
    }

    @Override
    public List<PermissionRequest> pendingPermissions(String sessionId) {
        return List.of();
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

    public void close() {
        executor.shutdown();
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

    /**
     * claude 流式回显（claude 专属路径，与 opencode 的 serve 事件无关）：加
     * {@code --include-partial-messages} 后 stdout 会出现 {@code stream_event} 行，内嵌原生
     * Anthropic 增量事件（text_delta / thinking_delta / input_json_delta）。工具块按
     * {@code index} 跟踪生命周期：start 登记 tool_use，delta 追加参数片段，stop 置 SUCCESS
     * ——前端按 call_id 聚合、按 argument_delta 拼参数，与 opencode 的 tool_call 契约一致。
     *
     * <p>每个进程一份实例：块索引在行间有状态，随 run 创建、随 run 丢弃。
     */
    private final class StreamEcho {
        private final String sessionId;
        private final Map<Integer, String> toolCallIds = new LinkedHashMap<>();
        private final Map<Integer, String> toolNames = new LinkedHashMap<>();
        /** 按 index 累积 input_json_delta 分片；content_block_stop 时拼出完整参数用于 todo journal。 */
        private final Map<Integer, StringBuilder> toolInputs = new LinkedHashMap<>();

        StreamEcho(String sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * V21 任务清单快照：todowrite 参数拼齐（content_block_stop）即 journal 到
         * session_todo，不等整回合 idle 落库；空数组=显式清空也落。journal 失败只吞掉
         * ——快照是派生数据（历史回填可补），绝不影响回合流。
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
            } catch (Exception ignored) {
            }
        }

        void line(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            try {
                Object parsed = MiniJson.parse(line.trim());
                if (!(parsed instanceof Map<?, ?> m)) {
                    return;
                }
                Map<String, Object> obj = cast(m);
                if (!"stream_event".equals(String.valueOf(obj.get("type")))
                        || !(obj.get("event") instanceof Map<?, ?> em)) {
                    return;
                }
                Map<String, Object> event = cast(em);
                Instant now = clock.now();
                String eventType = String.valueOf(event.get("type"));
                Integer index = event.get("index") instanceof Number n ? n.intValue() : null;
                if ("content_block_delta".equals(eventType)) {
                    if (!(event.get("delta") instanceof Map<?, ?> dm)) {
                        return;
                    }
                    Map<String, Object> delta = cast(dm);
                    String deltaType = String.valueOf(delta.get("type"));
                    if ("text_delta".equals(deltaType)) {
                        emitChunk(sessionId, new SessionStreamChunk.ContentChunk(sessionId,
                                String.valueOf(delta.get("text")), now));
                    } else if ("thinking_delta".equals(deltaType)) {
                        emitChunk(sessionId, new SessionStreamChunk.ThinkingChunk(sessionId,
                                String.valueOf(delta.get("thinking")), now));
                    } else if ("input_json_delta".equals(deltaType) && toolCallIds.containsKey(index)) {
                        String fragment = String.valueOf(delta.get("partial_json"));
                        toolInputs.computeIfAbsent(index, k -> new StringBuilder()).append(fragment);
                        emitChunk(sessionId, new SessionStreamChunk.ToolCallChunk(sessionId,
                                toolCallIds.get(index), toolNames.get(index), fragment, null, "RUNNING", now));
                    }
                } else if ("content_block_start".equals(eventType) && index != null) {
                    if (event.get("content_block") instanceof Map<?, ?> bm) {
                        Map<String, Object> block = cast(bm);
                        if ("tool_use".equals(String.valueOf(block.get("type")))) {
                            String callId = String.valueOf(block.getOrDefault("id", "call_" + System.nanoTime()));
                            String name = String.valueOf(block.getOrDefault("name", "unknown"));
                            toolCallIds.put(index, callId);
                            toolNames.put(index, name);
                            toolInputs.remove(index);
                            emitChunk(sessionId, new SessionStreamChunk.ToolCallChunk(sessionId,
                                    callId, name, "{}", null, "RUNNING", now));
                        }
                    }
                } else if ("content_block_stop".equals(eventType) && toolCallIds.containsKey(index)) {
                    StringBuilder accumulated = toolInputs.remove(index);
                    if (accumulated != null) {
                        journalTodoSnapshot(toolNames.get(index), accumulated.toString());
                    }
                    emitChunk(sessionId, new SessionStreamChunk.ToolCallChunk(sessionId,
                            toolCallIds.get(index), toolNames.get(index), "{}", null, "SUCCESS", now));
                }
            } catch (Exception ignored) {
            }
        }
    }    private void runSend(GateTask task, Session session, String message) {
        try (AutoCloseable ignored = ticketLocks.acquire(session.ticketNo())) {
            // Fresh read: a live model switch persisted after enqueue must still win.
            Session latest = sessions.find(session.id()).orElse(session);
            AgentConfig config = agentConfigs.find(latest.agentConfigId()).orElseThrow();
            Path clone = Path.of(session.clonePath());
            Path contextDir = clone.resolve(".git").resolve("gate-context");
            Path contextFile = contextDir.resolve("CLAUDE.md");
            Path mcpConfig = contextDir.resolve("mcp-config.json");
            tasks.update(progress(task, 10, "启动 claude"));
            Instant now = clock.now();
            insertUserMessage(session.id(), message, now);

            // Fresh read of the ticket row so 注入上下文 reflects edits made between turns.
            Ticket ctxTicket = tickets.find(latest.ticketNo()).orElse(null);
            writeContext(contextFile, latest.ticketNo(),
                    ctxTicket == null ? null : ctxTicket.targetRef(), config);

            // Always continue the recorded CLI conversation: a per-message spawn needs --resume
            // to stay in the same conversation, and the fresh read also picks up a session id a
            // still-running previous send has written after this task was enqueued.
            PlannedArgv planned = buildArgv(config, contextFile, mcpConfig,
                    latest.cliSessionId(), message, latest.overrideModel(), latest.overrideVariant());
            StreamEcho echo = new StreamEcho(session.id());
            ProcessRunner.ProcRun run = processRunner.runStreaming(planned.argv(), clone, Map.of(), Duration.ofMinutes(10),
                    echo::line, null);

            tasks.update(progress(task, 70, "解析 stream-json"));
            ParsedOutput parsed = parseStream(run.stdout());
            if (parsed.errorText() != null && !parsed.errorText().isBlank()) {
                // result.is_error：claude 以 exit 0 结束但回合失败（如网关 4xx），按错误落库。
                insertErrorMessage(session.id(), parsed.errorText(), now);
                emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "PROCESS_ERROR",
                        parsed.errorText(), now));
            } else if (!parsed.assistantTexts().isEmpty()) {
                List<String> texts = parsed.assistantTexts();
                for (int i = 0; i < texts.size(); i++) {
                    // 时间线（parts）与 usage 都挂最后一条 assistant；前面多条仅在
                    // 单个进程产出多回合时出现。
                    insertAssistantMessage(session.id(), texts.get(i),
                            i == texts.size() - 1 ? parsed.parts() : List.of(),
                            i == texts.size() - 1 ? parsed.usage() : null, parsed.degraded(), now,
                            i == texts.size() - 1 ? actualModelProvider(parsed, planned.requestProvider()) : null,
                            i == texts.size() - 1 ? actualModelId(parsed, planned.requestModelId()) : null);
                }
                if (parsed.usage() != null) {
                    emitChunk(session.id(), new SessionStreamChunk.UsageChunk(session.id(), parsed.usage(), now));
                }
            } else {
                String detail = run.timedOut()
                        ? "claude 运行超时被终止"
                        : "claude 未产生任何输出（exit=" + run.exitCode() + "）";
                insertErrorMessage(session.id(), detail, now);
                emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "PROCESS_ERROR", detail, now));
            }
            SessionUsage cumulative = session.cumulativeUsage().add(parsed.usage == null ? SessionUsage.EMPTY : parsed.usage);
            Session updated = session.withCumulativeUsage(cumulative);
            if (parsed.sessionId != null) {
                updated = updated.withCliSessionId(parsed.sessionId);
            }
            sessions.update(updated);
            writeback(updated);
            emitChunk(session.id(), new SessionStreamChunk.DoneChunk(session.id(), session.id(), now));
            tasks.update(success(task, "{\"session_id\":\"" + (parsed.sessionId == null ? "" : parsed.sessionId)
                    + "\",\"message_count\":" + sessions.findMessages(session.id()).size() + "}"));
        } catch (Throwable e) {
            emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "INTERNAL_ERROR", e.getMessage(), clock.now()));
            tasks.update(fail(task, e));
        } finally {
            // 必须覆盖正常完成、ErrorChunk、异常、中断所有出口，不得依赖 SSE 监听者
            decrementInFlight(session.id());
        }
    }

    private void writeback(Session session) {
        try {
            SessionUsage usage = session.cumulativeUsage();
            if (usage != null && usage.totalTokens() != null) {
                tickets.updateExecTokens(session.ticketNo(), usage.totalTokens(), "agent_cli", clock.now());
            }
        } catch (Exception ignored) {
            // Bypass-only (执行文档-后端-web §5.7): cost writeback must never fail the session task.
        }
    }

    /** Planned argv plus the request-side model attribution it pins (V22 fallback source). */
    private record PlannedArgv(List<String> argv, String requestProvider, String requestModelId) {
    }

    /** Splits a model ref into {provider, bare id}; a bare id (or blank) yields a null provider. */
    private static String[] splitModelHalves(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return new String[]{null, null};
        }
        int slash = modelRef.indexOf('/');
        if (slash <= 0 || slash >= modelRef.length() - 1) {
            return new String[]{null, modelRef.trim()};
        }
        return new String[]{modelRef.substring(0, slash).trim(), modelRef.substring(slash + 1).trim()};
    }

    private PlannedArgv buildArgv(AgentConfig config, Path contextFile, Path mcpConfig,
                                  String resumeSessionId, String prompt) {
        return buildArgv(config, contextFile, mcpConfig, resumeSessionId, prompt, null, null);
    }

    private PlannedArgv buildArgv(AgentConfig config, Path contextFile, Path mcpConfig,
                                  String resumeSessionId, String prompt, String overrideModel,
                                  String overrideVariant) {
        List<String> argv = new ArrayList<>();
        argv.add(claudeExecutable);
        argv.addAll(claudePrefix);
        argv.add("-p");
        // The prompt must stay a positional argument: with --input-format=stream-json claude
        // ignores it and waits for stdin (closed by the runner) — it then exits 0 with no
        // output and never creates a CLI session.
        argv.add("--output-format");
        argv.add("stream-json");
        // claude CLI hard requirement: --print + stream-json output refuses to start without
        // --verbose ("When using --print, --output-format=stream-json requires --verbose").
        argv.add("--verbose");
        // 流式回显：stream-json 默认只输出整段的 assistant 行（整个进程退出前工作台一片
        // 空白，而第三方转录工具能看到逐字内容）；该 flag 让 claude 额外发出 stream_event
        // 增量事件，由 StreamEcho 实时转发给 SSE。
        argv.add("--include-partial-messages");
        // No model flag means Claude Code resolves its own provider/model configuration. The
        // agent profile may still opt into an explicit model override when one is selected;
        // a live per-session switch (会话内实时切换) beats the profile default. Agent defaults
        // carry a "provider/model" ref — claude --model wants the bare model id: the gateway
        // routes by model name, the provider half is gate-internal.
        String effectiveModel = overrideModel != null && !overrideModel.isBlank()
                ? overrideModel.trim() : config.model();
        // CLI 自管模型哨兵：Agent 绑定 cli-default 且 model 与 provider 同名（无 provider/ 斜杠）
        // 表示模型由 claude 自身配置决定——不带 --model，否则网关会收到字面量 "cli-default"。
        boolean cliManagedModel = effectiveModel != null && !effectiveModel.contains("/")
                && effectiveModel.equalsIgnoreCase(config.providerId());
        if (effectiveModel != null && !effectiveModel.isBlank() && !cliManagedModel) {
            argv.add("--model");
            argv.add(bareModelId(effectiveModel));
        }
        // V22：请求值兜底——记录本次 spawn 计划钉住的模型（裸 id + gate 内部 provider 段）；
        // cliManagedModel（CLI 自管）与空配置不预置，落库依赖上游 stream-json 的实际值。
        String[] requestHalves = cliManagedModel ? new String[]{null, null}
                : splitModelHalves(effectiveModel);
        // 推理强度必须显式钉住：不传时 claude 按自己的默认档位发 output_config.effort，
        // 严格网关会以 400 output_config.effort must be one of: low, medium, high, max 拒绝
        // （2.1.240 实测）；显式传入枚举内档位后该 400 消失。候选档位由会话模型目录提供。
        if (overrideVariant != null && !overrideVariant.isBlank()) {
            argv.add("--effort");
            argv.add(overrideVariant.trim());
        }
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            argv.add("--resume");
            argv.add(resumeSessionId);
        }
        // 系统提示词注入：context file 由 writeContext 组合 systemPrompt + 项目/工单上下文，
        // 只在确实有内容可注入时才追加（inject_context 关闭且无自定义提示词 → 不写文件）。
        if (Files.exists(contextFile)) {
            argv.add("--append-system-prompt-file");
            argv.add(contextFile.toString());
        }
        if (config.extraFlags() != null) {
            argv.addAll(config.extraFlags());
        }
        if (Files.exists(mcpConfig)) {
            argv.add("--mcp-config");
            argv.add(mcpConfig.toString());
            argv.add("--strict-mcp-config");
        }
        argv.add("--permission-mode");
        argv.add("acceptEdits");
        if (prompt != null) {
            argv.add(prompt);
        }
        return new PlannedArgv(argv, requestHalves[0], requestHalves[1]);
    }

    /**
     * "provider/model" agent default → bare model id (mirrors the frontend splitModelRef rule:
     * first slash separates, a bare id or a trailing slash passes through unchanged).
     */
    private static String bareModelId(String modelRef) {
        int slash = modelRef.indexOf('/');
        if (slash <= 0 || slash >= modelRef.length() - 1) {
            return modelRef;
        }
        return modelRef.substring(slash + 1);
    }

    /**
     * Writes the effective append-system-prompt content: the optional AgentConfig.systemPrompt
     * plus — when 注入开关 (injectContext) is on — the project/ticket context block. Deletes any
     * stale file when there is nothing to inject so buildArgv's existence check stays truthful.
     */
    private void writeContext(Path file, String ticketNo, String targetRef, AgentConfig config) {
        try {
            Ticket ticket = tickets == null ? null : tickets.find(ticketNo).orElse(null);
            Project project = null;
            if (projects != null && ticket != null && ticket.projectId() != null) {
                project = projects.find(ticket.projectId()).orElse(null);
            }
            TicketStageChangeRepository.StageChangeRow latestRevive =
                    restarts == null ? null : restarts.latestRevive(ticketNo).orElse(null);
            String content = AgentContextPrompt.compose(config, ticketNo, targetRef, ticket, project, latestRevive);
            Files.createDirectories(file.getParent());
            if (content.isBlank()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot write claude context file " + file, e);
        }
    }

    /** 上游实际值优先，缺失时回退发送端请求值（口径确认：实际优先、请求兜底）。 */
    private static String actualModelProvider(ParsedOutput parsed, String requestProvider) {
        return parsed.modelProvider() != null ? parsed.modelProvider() : requestProvider;
    }

    private static String actualModelId(ParsedOutput parsed, String requestModelId) {
        return parsed.modelId() != null ? parsed.modelId() : requestModelId;
    }

    private void writeMcpConfig(Path file, Map<String, String> env) {
        try {
            String token = env == null ? "" : env.getOrDefault("GATE_DOMAIN_TOKEN", "");
            // Without a token the MCP server refuses to start (it demands GATE_DOMAIN_TOKEN,
            // §6.1) and without gateToml we cannot build a spawnable command ("gate" is not on
            // PATH in this deployment) — delete any stale file so buildArgv's existence check
            // stays truthful instead of passing claude a config that can never come up.
            if (gateToml == null || token.isBlank()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            String json = GateMcpProvisioning.claudeConfigJson(
                    GateMcpProvisioning.serveArgv(gateToml), token);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot write mcp config " + file, e);
        }
    }

    private void insertUserMessage(String sessionId, String text, Instant at) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.USER,
                text == null ? "" : text, List.of(), null, false, at));
    }

    private void insertAssistantMessage(String sessionId, String text, List<TurnPart> parts,
                                        SessionUsage usage, boolean degraded, Instant at,
                                        String modelProvider, String modelId) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                text, List.of(), usage, degraded, at, parts, modelProvider, modelId));
    }

    private void insertErrorMessage(String sessionId, String text, Instant at) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ERROR,
                text == null ? "claude failed" : text, List.of(), null, true, at));
    }

    /**
     * claude stream-json 的终态解析：每个 {@code assistant} 行是一个完整回合（一个进程可产生
     * 多个回合），{@code result} 行携带权威 usage 与 is_error。增量事件（stream_event）不参与
     * 终态解析——它们只经 {@link StreamEcho} 实时转发。
     */
    private static ParsedOutput parseStream(String stdout) {
        List<String> assistantTexts = new ArrayList<>();
        List<TurnPart> parts = new ArrayList<>();
        String sessionId = null;
        SessionUsage usage = null;
        String errorText = null;
        boolean degraded = false;
        String modelProvider = null;
        String modelId = null;
        if (stdout == null || stdout.isBlank()) {
            return new ParsedOutput(null, assistantTexts, null, true, null, List.of());
        }
        try {
            for (String line : stdout.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                Object parsed = MiniJson.parse(line.trim());
                if (!(parsed instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> obj = cast(m);
                Object sid = obj.get("session_id");
                if (sid != null) {
                    sessionId = String.valueOf(sid);
                }
                String type = String.valueOf(obj.get("type"));
                if ("assistant".equals(type)) {
                    String text = extractText(obj.get("message"), null);
                    if (text != null && !text.isBlank()) {
                        assistantTexts.add(text);
                        parts.add(TurnPart.text(text));
                    }
                    // 时间线：同一 assistant 行里的 thinking / tool_use 块按到达序入列，
                    // 工具的终态输出在对应 user 行（tool_result）到达时回填到最近一个空槽。
                    if (obj.get("message") instanceof Map<?, ?> mm) {
                        Object contentObj = cast(mm).get("content");
                        if (contentObj instanceof List<?> blocks) {
                            for (Object b : blocks) {
                                if (!(b instanceof Map<?, ?> bm)) {
                                    continue;
                                }
                                Map<String, Object> block = cast(bm);
                                switch (String.valueOf(block.get("type"))) {
                                    case "thinking" -> {
                                        if (block.get("thinking") instanceof String t && !t.isBlank()) {
                                            parts.add(TurnPart.thinking(t));
                                        }
                                    }
                                    case "tool_use" -> parts.add(TurnPart.tool(
                                            String.valueOf(block.getOrDefault("name", "unknown")),
                                            block.get("input") == null ? "{}" : MiniJson.write(block.get("input")),
                                            null));
                                    default -> {
                                    }
                                }
                            }
                        }
                        SessionUsage messageUsage = extractUsage(cast(mm).get("usage"));
                        if (messageUsage != null) {
                            usage = messageUsage;
                        }
                        // V22：CLI 上报的实际模型（"model" 可能带 provider/ 前缀）——权威来源，
                        // 逐行覆盖（一回合多 assistant 行取最后一行的值）。
                        String reportedModel = strField(cast(mm), "model");
                        if (reportedModel != null && !reportedModel.isBlank()) {
                            String[] halves = splitModelHalves(reportedModel);
                            modelProvider = halves[0];
                            modelId = halves[1];
                        }
                    }
                } else if ("user".equals(type)) {
                    // tool_result 行：把输出回填到时间线里最近的未填输出工具槽。
                    if (obj.get("message") instanceof Map<?, ?> mm
                            && cast(mm).get("content") instanceof List<?> blocks) {
                        for (Object b : blocks) {
                            if (!(b instanceof Map<?, ?> bm)
                                    || !"tool_result".equals(String.valueOf(cast(bm).get("type")))) {
                                continue;
                            }
                            String output = stringifyToolResult(cast(bm).get("content"));
                            for (int i = parts.size() - 1; i >= 0; i--) {
                                TurnPart p = parts.get(i);
                                if (p.isTool() && (p.resultJson() == null || p.resultJson().isBlank())) {
                                    parts.set(i, TurnPart.tool(p.name(), p.argumentsJson(), output));
                                    break;
                                }
                            }
                        }
                    }
                } else if ("result".equals(type)) {
                    SessionUsage resultUsage = extractUsage(obj.get("usage"));
                    if (resultUsage != null) {
                        usage = resultUsage;
                    }
                    // V22：result 行同样上报实际模型——通常与 assistant 行一致，兜住
                    // assistant 行缺失 model 字段的 CLI 版本。
                    String resultModel = strField(obj, "model");
                    if (resultModel != null && !resultModel.isBlank()) {
                        String[] halves = splitModelHalves(resultModel);
                        modelProvider = halves[0];
                        modelId = halves[1];
                    }
                    if (Boolean.TRUE.equals(obj.get("is_error"))) {
                        errorText = String.valueOf(obj.get("result"));
                    }
                }
            }
        } catch (Exception e) {
            degraded = true;
        }
        return new ParsedOutput(sessionId, assistantTexts, usage, degraded, errorText, parts,
                modelProvider, modelId);
    }

    /** tool_result.content 可能是字符串或块数组；归一成纯文本供 OUT 展示。 */
    private static String stringifyToolResult(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("text") instanceof String t) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return content == null ? null : String.valueOf(content);
    }

    /** claude/兼容网关的 usage 形状归一：input/output 优先，缺 total 时以 input+output 补齐。 */
    private static SessionUsage extractUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> um)) {
            return null;
        }
        Map<String, Object> usageMap = cast(um);
        Long prompt = longOrNull(usageMap.get("input_tokens"));
        if (prompt == null) {
            prompt = longOrNull(usageMap.get("prompt_tokens"));
        }
        Long completion = longOrNull(usageMap.get("output_tokens"));
        if (completion == null) {
            completion = longOrNull(usageMap.get("completion_tokens"));
        }
        Long total = longOrNull(usageMap.get("total_tokens"));
        if (total == null && (prompt != null || completion != null)) {
            total = (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
        }
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        return new SessionUsage(prompt, completion, total);
    }

    private static String extractText(Object message, Object text) {
        if (text instanceof String s && !s.isBlank()) {
            return s;
        }
        if (message instanceof Map<?, ?> mm) {
            Map<String, Object> map = cast(mm);
            Object content = map.get("content");
            if (content instanceof String s) {
                return s;
            }
            if (content instanceof List<?> list) {
                StringBuilder b = new StringBuilder();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> im) {
                        Map<String, Object> itemMap = cast(im);
                        if ("text".equals(itemMap.get("type"))) {
                            Object t = itemMap.get("text");
                            if (t != null) {
                                b.append(t);
                            }
                        }
                    }
                }
                return b.isEmpty() ? null : b.toString();
            }
        }
        return null;
    }

    private static GateTask progress(GateTask task, int percent, String step) {
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.RUNNING, task.startedAt(), null,
                "{\"percent\":" + percent + ",\"label\":\"" + escapeJson(step) + "\"}", null);
    }

    private static GateTask success(GateTask task, String resultJson) {
        Instant now = Instant.now();
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.SUCCEEDED, task.startedAt(), now, resultJson, null);
    }

    private static GateTask fail(GateTask task, Throwable err) {
        Instant now = Instant.now();
        String msg = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.FAILED, task.startedAt(), now, null,
                "{\"error\":\"" + escapeJson(msg) + "\"}");
    }

    private static Long longOrNull(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            typed.put(String.valueOf(e.getKey()), (Object) e.getValue());
        }
        return typed;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Null-safe string field read: non-String scalars stringify, null stays null. */
    private static String strField(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : String.valueOf(val);
    }

    private record ParsedOutput(String sessionId, List<String> assistantTexts, SessionUsage usage,
                                boolean degraded, String errorText, List<TurnPart> parts,
                                String modelProvider, String modelId) {

        /** Legacy shape for the degraded early-return (no model attribution). */
        ParsedOutput(String sessionId, List<String> assistantTexts, SessionUsage usage,
                     boolean degraded, String errorText, List<TurnPart> parts) {
            this(sessionId, assistantTexts, usage, degraded, errorText, parts, null, null);
        }
    }
}
