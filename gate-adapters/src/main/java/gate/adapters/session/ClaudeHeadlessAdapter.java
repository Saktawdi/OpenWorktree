package gate.adapters.session;

import gate.application.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentConfig;
import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.domain.task.GateTask;
import gate.domain.task.GateTaskStatus;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.Clock;
import gate.ports.ProcessRunner;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final TaskRegistry tasks;
    private final TicketLockManager ticketLocks;
    private final Clock clock;
    private final String claudeExecutable;
    private final List<String> claudePrefix;
    private final ExecutorService executor;

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
        this.processRunner = processRunner;
        this.agentConfigs = agentConfigs;
        this.sessions = sessions;
        this.tickets = tickets;
        this.tasks = tasks;
        this.ticketLocks = ticketLocks;
        this.clock = clock;
        this.claudeExecutable = claudeExecutable;
        this.claudePrefix = claudePrefix == null ? List.of() : List.copyOf(claudePrefix);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claude-session");
            t.setDaemon(true);
            return t;
        });
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
        writeContext(contextFile, request, config);
        writeMcpConfig(mcpConfig, request.env());

        List<String> argv = buildArgv(config, contextFile, mcpConfig, null, request.initialPrompt());
        ProcessRunner.ProcRun run = processRunner.run(argv, clone, request.env(), Duration.ofMinutes(10));

        ParsedOutput parsed = parseStream(run.stdout());
        Session session = new Session(
                sessionId, request.ticketNo(), config.id(), AgentCli.CLAUDE, SessionStatus.ACTIVE,
                parsed.sessionId, request.clonePath(), -1, now, null, SessionUsage.EMPTY);
        sessions.insert(session);

        // Always record the user message; record the assistant message only when something was parsed.
        insertUserMessage(sessionId, request.initialPrompt(), now);
        Session withUsage = session;
        if (parsed.assistantText != null || parsed.usage != null) {
            insertAssistantMessage(sessionId, parsed.assistantText == null ? "" : parsed.assistantText,
                    parsed.usage, parsed.degraded, now);
            if (parsed.usage != null) {
                withUsage = withUsage.withCumulativeUsage(parsed.usage);
                sessions.update(withUsage);
                writeback(withUsage);
            }
        }
        if (!run.ok() && parsed.sessionId == null) {
            insertErrorMessage(sessionId, run.stderrFirstLine(), now);
            sessions.update(withUsage.withStatus(SessionStatus.ABORTED).withFinishedAt(now));
        }
        return sessions.find(sessionId).orElse(withUsage);
    }

    @Override
    public String sendMessage(SendRequest request) {
        Session session = sessions.find(request.sessionId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + request.sessionId()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        executor.submit(() -> runSend(task, session, request.message(), request.resume()));
        return task.id();
    }

    @Override
    public void abort(String sessionId) {
        sessions.find(sessionId).ifPresent(s -> {
            Session aborted = s.withStatus(SessionStatus.ABORTED).withFinishedAt(clock.now());
            sessions.update(aborted);
            writeback(aborted);
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

    public void close() {
        executor.shutdown();
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

    private void runSend(GateTask task, Session session, String message, boolean resume) {
        try (AutoCloseable ignored = ticketLocks.acquire(session.ticketNo())) {
            AgentConfig config = agentConfigs.find(session.agentConfigId()).orElseThrow();
            Path clone = Path.of(session.clonePath());
            Path contextDir = clone.resolve(".git").resolve("gate-context");
            Path contextFile = contextDir.resolve("CLAUDE.md");
            Path mcpConfig = contextDir.resolve("mcp-config.json");
            tasks.update(progress(task, 10, "启动 claude"));
            List<String> argv = buildArgv(config, contextFile, mcpConfig,
                    resume ? session.cliSessionId() : session.cliSessionId(), message);
            ProcessRunner.ProcRun run = processRunner.run(argv, clone, Map.of(), Duration.ofMinutes(10));
            tasks.update(progress(task, 70, "解析 stream-json"));
            ParsedOutput parsed = parseStream(run.stdout());
            Instant now = clock.now();
            insertUserMessage(session.id(), message, now);
            if (parsed.assistantText != null || parsed.usage != null) {
                insertAssistantMessage(session.id(),
                        parsed.assistantText == null ? "" : parsed.assistantText,
                        parsed.usage, parsed.degraded, now);
            } else {
                insertErrorMessage(session.id(), run.stderrFirstLine(), now);
            }
            SessionUsage cumulative = session.cumulativeUsage().add(parsed.usage == null ? SessionUsage.EMPTY : parsed.usage);
            Session updated = session.withCumulativeUsage(cumulative);
            if (parsed.sessionId != null) {
                updated = updated.withCliSessionId(parsed.sessionId);
            }
            sessions.update(updated);
            writeback(updated);
            tasks.update(success(task, "{\"session_id\":\"" + (parsed.sessionId == null ? "" : parsed.sessionId)
                    + "\",\"message_count\":" + sessions.findMessages(session.id()).size() + "}"));
        } catch (Throwable e) {
            tasks.update(fail(task, e));
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
        List<String> argv = new ArrayList<>();
        argv.add(claudeExecutable);
        argv.addAll(claudePrefix);
        argv.add("-p");
        argv.add("--input-format");
        argv.add("stream-json");
        argv.add("--output-format");
        argv.add("stream-json");
        // No model flag means Claude Code resolves its own provider/model configuration. The
        // agent profile may still opt into an explicit model override when one is selected.
        if (config.model() != null && !config.model().isBlank()) {
            argv.add("--model");
            argv.add(config.model());
        }
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            argv.add("--resume");
            argv.add(resumeSessionId);
        }
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
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

    private void writeContext(Path file, StartRequest request, AgentConfig config) {
        try {
            Files.createDirectories(file.getParent());
            String content = "# Gate 工单上下文\n\n"
                    + "- 工单号: " + request.ticketNo() + "\n"
                    + "- 目标分支: " + request.targetRef() + "\n"
                    + "- AgentConfig: " + config.id() + " ("
                    + (config.model() == null ? "CLI 默认设置" : config.model()) + ")\n"
                    + "- 你无权 push 到权威库；预提审请调 presubmit_create MCP 工具\n"
                    + "- tree_hash 约定: 审核锚定不可变 tree，修改后需重新预提审\n";
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot write claude context file " + file, e);
        }
    }

    private void writeMcpConfig(Path file, Map<String, String> env) {
        try {
            Files.createDirectories(file.getParent());
            String token = env == null ? "" : env.getOrDefault("GATE_DOMAIN_TOKEN", "");
            String json = "{\"mcpServers\":{\"gate\":{\"command\":\"gate\",\"args\":[\"mcp\",\"serve\"],"
                    + "\"env\":{\"GATE_DOMAIN_TOKEN\":\"" + escapeJson(token) + "\"}}}}";
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String extractText(Object message, Object directText) {
        if (directText != null) {
            return String.valueOf(directText);
        }
        if (message instanceof Map<?, ?> m) {
            Object content = m.get("content");
            if (content instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> cm) {
                        Map<String, Object> cmm = cast(cm);
                        Object type = cmm.get("type");
                        if (type == null || "text".equals(String.valueOf(type))) {
                            Object text = cmm.get("text");
                            if (text != null) {
                                return String.valueOf(text);
                            }
                        }
                    }
                }
            }
        }
        return null;
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
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static GateTask progress(GateTask task, int percent, String label) {
        String payload = "{\"percent\":" + percent + ",\"label\":\"" + label + "\"}";
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.RUNNING, task.startedAt(), null, payload, null);
    }

    private static GateTask success(GateTask task, String resultJson) {
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.SUCCEEDED, task.startedAt(), null, resultJson, null);
    }

    private static GateTask fail(GateTask task, Throwable e) {
        String message = e instanceof GateException ge ? ge.getMessage() : "internal error";
        return new GateTask(task.id(), task.type(), task.ticketNo(), task.sessionId(),
                GateTaskStatus.FAILED, task.startedAt(), null, null,
                "{\"error_code\":" + (e instanceof GateException ge ? ge.code().code() : 70)
                        + ",\"error\":\"" + (e instanceof GateException ge2 ? ge2.code().name() : "INTERNAL")
                        + "\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    private record ParsedOutput(String sessionId, String assistantText, SessionUsage usage, boolean degraded) {
    }
}
