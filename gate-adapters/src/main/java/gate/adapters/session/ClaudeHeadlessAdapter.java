package gate.adapters.session;

import gate.application.MiniJson;
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
import gate.domain.session.PermissionRequest;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.domain.ticket.Ticket;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.Clock;
import gate.ports.ProcessRunner;
import gate.ports.ProjectRepository;
import gate.ports.SessionRepository;
import gate.ports.TaskRegistry;
import gate.ports.TicketLockManager;
import gate.ports.TicketRepository;
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
 * Claude headless adapter (执行文档-后端-web §5.3.2, ADR-12): per-message {@code claude -p} spawn with
 * {@code stream-json} in/out and {@code --resume} for continuation.
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
        this(processRunner, agentConfigs, sessions, tickets, null, tasks, ticketLocks, clock,
                claudeExecutable, claudePrefix, null);
    }

    /** Full constructor: {@code projects} is optional (null skips the 项目 section of the injected context). */
    public ClaudeHeadlessAdapter(ProcessRunner processRunner,
                                 AgentConfigRepository agentConfigs,
                                 SessionRepository sessions,
                                 TicketRepository tickets,
                                 ProjectRepository projects,
                                 TaskRegistry tasks,
                                 TicketLockManager ticketLocks,
                                 Clock clock,
                                 String claudeExecutable,
                                 List<String> claudePrefix,
                                 Path gateToml) {
        this.processRunner = processRunner;
        this.agentConfigs = agentConfigs;
        this.sessions = sessions;
        this.tickets = tickets;
        this.projects = projects;
        this.tasks = tasks;
        this.ticketLocks = ticketLocks;
        this.clock = clock;
        this.claudeExecutable = claudeExecutable;
        this.claudePrefix = claudePrefix == null ? List.of() : List.copyOf(claudePrefix);
        this.gateToml = gateToml;
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
            return startLocked(request);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "session start failed", e);
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

        List<String> argv = buildArgv(config, contextFile, mcpConfig, null, request.initialPrompt());
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

        ProcessRunner.ProcRun run = processRunner.runStreaming(argv, clone, request.env(), Duration.ofMinutes(10),
                line -> handleStreamLine(sessionId, line), null);

        ParsedOutput parsed = parseStream(run.stdout());
        Session withUsage = session;
        if (parsed.sessionId != null) {
            withUsage = withUsage.withCliSessionId(parsed.sessionId);
        }
        if (parsed.assistantText != null || parsed.usage != null) {
            insertAssistantMessage(sessionId, parsed.assistantText == null ? "" : parsed.assistantText,
                    parsed.usage, parsed.degraded, now);
            if (parsed.usage != null) {
                withUsage = withUsage.withCumulativeUsage(parsed.usage);
                sessions.update(withUsage);
                writeback(withUsage);
                emitChunk(sessionId, new SessionStreamChunk.UsageChunk(sessionId, parsed.usage, now));
            }
        }
        if (!run.ok() && parsed.sessionId == null) {
            insertErrorMessage(sessionId, run.stderrFirstLine(), now);
            sessions.update(withUsage.withStatus(SessionStatus.ABORTED).withFinishedAt(now));
            emitChunk(sessionId, new SessionStreamChunk.ErrorChunk(sessionId, "PROCESS_ERROR", run.stderrFirstLine(), now));
        } else {
            emitChunk(sessionId, new SessionStreamChunk.DoneChunk(sessionId, sessionId, now));
        }
        return sessions.find(sessionId).orElse(withUsage);
    }

    @Override
    public String sendMessage(SendRequest request) {
        Session session = sessions.find(request.sessionId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + request.sessionId()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        // 入队即算运行：登记发生在提交 executor 之前，排队等待也算运行中；同一 session 多次 send 用引用计数
        incrementInFlight(session.id());
        executor.submit(() -> runSend(task, session, request.message(), request.resume()));
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

    private void handleStreamLine(String sessionId, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            Object parsed = MiniJson.parse(line.trim());
            if (!(parsed instanceof Map<?, ?> m)) {
                return;
            }
            Map<String, Object> obj = cast(m);
            Instant now = clock.now();

            String type = String.valueOf(obj.get("type"));
            if ("content_block_delta".equals(type)) {
                Object deltaObj = obj.get("delta");
                if (deltaObj instanceof Map<?, ?> dm) {
                    Map<String, Object> delta = cast(dm);
                    String deltaType = String.valueOf(delta.get("type"));
                    if ("text_delta".equals(deltaType)) {
                        String text = String.valueOf(delta.get("text"));
                        emitChunk(sessionId, new SessionStreamChunk.ContentChunk(sessionId, text, now));
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thinking = String.valueOf(delta.get("thinking"));
                        emitChunk(sessionId, new SessionStreamChunk.ThinkingChunk(sessionId, thinking, now));
                    }
                }
            } else if ("tool_use".equals(type) || "tool_call".equals(type)) {
                String callId = String.valueOf(obj.getOrDefault("id", "call_" + System.currentTimeMillis()));
                String name = String.valueOf(obj.getOrDefault("name", "unknown"));
                String input = obj.get("input") == null ? "{}" : String.valueOf(obj.get("input"));
                emitChunk(sessionId, new SessionStreamChunk.ToolCallChunk(sessionId, callId, name, input, null, "RUNNING", now));
            }
        } catch (Exception ignored) {
        }
    }

    private void runSend(GateTask task, Session session, String message, boolean resume) {
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

            List<String> argv = buildArgv(config, contextFile, mcpConfig,
                    resume ? session.cliSessionId() : session.cliSessionId(), message,
                    latest.overrideModel());
            ProcessRunner.ProcRun run = processRunner.runStreaming(argv, clone, Map.of(), Duration.ofMinutes(10),
                    line -> handleStreamLine(session.id(), line), null);

            tasks.update(progress(task, 70, "解析 stream-json"));
            ParsedOutput parsed = parseStream(run.stdout());
            if (parsed.assistantText != null || parsed.usage != null) {
                insertAssistantMessage(session.id(),
                        parsed.assistantText == null ? "" : parsed.assistantText,
                        parsed.usage, parsed.degraded, now);
                if (parsed.usage != null) {
                    emitChunk(session.id(), new SessionStreamChunk.UsageChunk(session.id(), parsed.usage, now));
                }
            } else {
                insertErrorMessage(session.id(), run.stderrFirstLine(), now);
                emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "PROCESS_ERROR", run.stderrFirstLine(), now));
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

    private List<String> buildArgv(AgentConfig config, Path contextFile, Path mcpConfig,
                                   String resumeSessionId, String prompt) {
        return buildArgv(config, contextFile, mcpConfig, resumeSessionId, prompt, null);
    }

    private List<String> buildArgv(AgentConfig config, Path contextFile, Path mcpConfig,
                                   String resumeSessionId, String prompt, String overrideModel) {
        List<String> argv = new ArrayList<>();
        argv.add(claudeExecutable);
        argv.addAll(claudePrefix);
        argv.add("-p");
        argv.add("--input-format");
        argv.add("stream-json");
        argv.add("--output-format");
        argv.add("stream-json");
        // claude CLI hard requirement: --print + stream-json output refuses to start without
        // --verbose ("When using --print, --output-format=stream-json requires --verbose").
        argv.add("--verbose");
        // No model flag means Claude Code resolves its own provider/model configuration. The
        // agent profile may still opt into an explicit model override when one is selected;
        // a live per-session switch (会话内实时切换) beats the profile default.
        String effectiveModel = overrideModel != null && !overrideModel.isBlank()
                ? overrideModel.trim() : config.model();
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            argv.add("--model");
            argv.add(effectiveModel);
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
        return argv;
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
            String content = AgentContextPrompt.compose(config, ticketNo, targetRef, ticket, project);
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

    private void insertAssistantMessage(String sessionId, String text, SessionUsage usage, boolean degraded, Instant at) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                text, List.of(), usage, degraded, at));
    }

    private void insertErrorMessage(String sessionId, String text, Instant at) {
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), sessionId, Role.ERROR,
                text == null ? "claude failed" : text, List.of(), null, true, at));
    }

    private static ParsedOutput parseStream(String stdout) {
        String sessionId = null;
        String assistantText = null;
        SessionUsage usage = null;
        boolean degraded = false;
        if (stdout == null || stdout.isBlank()) {
            return new ParsedOutput(null, null, null, true);
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
                if (assistantText == null) {
                    assistantText = extractText(obj.get("message"), obj.get("text"));
                }
                Object usageObj = obj.get("usage");
                if (usageObj instanceof Map<?, ?> um) {
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
                    if (prompt != null || completion != null || total != null) {
                        usage = new SessionUsage(prompt, completion, total);
                    }
                }
            }
        } catch (Exception e) {
            degraded = true;
        }
        return new ParsedOutput(sessionId, assistantText, usage, degraded);
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

    private record ParsedOutput(String sessionId, String assistantText, SessionUsage usage, boolean degraded) {
    }
}
