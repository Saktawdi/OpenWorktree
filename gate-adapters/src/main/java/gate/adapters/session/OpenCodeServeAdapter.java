package gate.adapters.session;

import gate.adapters.io.AdapterLog;
import gate.adapters.io.ServePidRegistry;
import gate.application.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionStreamChunk;
import gate.domain.session.SessionUsage;
import gate.domain.session.ToolCall;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private final ProcessRunner processRunner;
    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessions;
    private final TicketRepository tickets;
    private final TaskRegistry tasks;
    private final TicketLockManager ticketLocks;
    private final Clock clock;
    private final PortAllocator ports;
    private final Duration startTimeout;
    private final String opencodeExecutable;
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
    private final Map<String, java.util.Set<java.util.function.Consumer<SessionStreamChunk>>> listeners = new ConcurrentHashMap<>();
    private final AdapterLog log;
    private final ServePidRegistry pidRegistry;

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
        this.processRunner = processRunner;
        this.agentConfigs = agentConfigs;
        this.sessions = sessions;
        this.tickets = tickets;
        this.tasks = tasks;
        this.ticketLocks = ticketLocks;
        this.clock = clock;
        this.ports = ports;
        this.opencodeExecutable = opencodeExecutable;
        this.startTimeout = Duration.ofSeconds(startTimeoutSeconds);
        this.log = log == null ? AdapterLog.noop() : log;
        this.pidRegistry = pidRegistry == null ? new ServePidRegistry(null) : pidRegistry;
        this.pidRegistry.sweepOrphans();
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
    public AutoCloseable attachListener(String sessionId, java.util.function.Consumer<SessionStreamChunk> listener) {
        java.util.Set<java.util.function.Consumer<SessionStreamChunk>> set =
                listeners.computeIfAbsent(sessionId, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()));
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
        return () -> set.remove(listener);
    }

    private void emitChunk(String sessionId, SessionStreamChunk chunk) {
        java.util.Set<java.util.function.Consumer<SessionStreamChunk>> set = listeners.get(sessionId);
        if (set != null) {
            for (java.util.function.Consumer<SessionStreamChunk> listener : set) {
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
            AgentConfig config = agentConfigs.find(request.agentConfigId())
                    .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                            "no such agent config: " + request.agentConfigId()));
            int port = ports.allocate();
            try {
                if (opencodeExecutable != null && !opencodeExecutable.isBlank()) {
                    // A leftover opencode serve from a previous backend run silently answers /health
                    // on this port with STALE in-memory config; our own spawned process loses the
                    // bind race and dies while waitHealthy talks to the orphan instead. Refuse.
                    assertPortFree(port);
                    spawnServe(port, request.clonePath());
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
        sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                Role.USER, request.message(), List.of(), null, false, clock.now()));
        GateTask task = tasks.register("session-send", session.ticketNo(), session.id());
        executor.submit(() -> runSend(task, session, request.message()));
        return task.id();
    }

    @Override
    public void abort(String sessionId) {
        sessions.find(sessionId).ifPresent(s -> {
            stopUpstream(sessionId);
            Integer port = sessionPorts.get(sessionId);
            if (port != null && s.cliSessionId() != null) {
                postQuietly("http://127.0.0.1:" + port + "/session/" + s.cliSessionId() + "/abort", "{}");
            }
            killProcess(port);
            if (port != null) {
                ports.release(port);
                sessionPorts.remove(sessionId);
            }
            sessions.update(s.withStatus(SessionStatus.ABORTED).withFinishedAt(clock.now()));
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
        for (Upstream up : upstreams.values()) {
            up.stop();
        }
        upstreams.clear();
        watchdog.shutdownNow();
        for (Integer port : List.copyOf(sessionPorts.values())) {
            killProcess(port);
            ports.release(port);
        }
        sessionPorts.clear();
        executor.shutdown();
    }

    // -------------------------------------------------------------------------------------------
    // Send path: fire prompt_async, streaming happens on the upstream event reader
    // -------------------------------------------------------------------------------------------

    private void runSend(GateTask task, Session session, String message) {
        try (AutoCloseable ignored = ticketLocks.acquire(session.ticketNo())) {
            Integer port = sessionPorts.get(session.id());
            if (port == null || session.cliSessionId() == null) {
                throw new GateException(GateErrorCode.USAGE, "session has no opencode endpoint");
            }
            AgentConfig config = agentConfigs.find(session.agentConfigId()).orElseThrow();
            tasks.update(progress(task, 20, "触发 opencode 回合"));
            String body = messageBody(config, message);
            Upstream up = upstreams.get(session.id());
            if (up != null) {
                // Reset BEFORE firing the request: once prompt_async lands, events for this turn
                // can arrive within milliseconds and must not be wiped by post-send cleanup.
                up.assistantPersistedSinceSend = false;
                up.pendingErrorName = null;
                up.pendingErrorMessage = null;
                up.turnText.setLength(0);
                synchronized (up.turnTools) {
                    up.turnTools.clear();
                }
                up.turnUsage = null;
                up.turnHasNewContent = false;
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
            tasks.update(fail(task, e));
        }
    }

    static String messageBody(AgentConfig config, String message) {
        StringBuilder body = new StringBuilder("{\"parts\":[{\"type\":\"text\",\"text\":\"")
                .append(escapeJson(message)).append("\"}]");
        ModelRef model = ModelRef.parse(config.model());
        if (model != null) {
            body.append(",\"model\":{\"providerID\":\"")
                    .append(escapeJson(model.providerId()))
                    .append("\",\"modelID\":\"")
                    .append(escapeJson(model.modelId()))
                    .append("\"}");
        }
        String agent = agentFlag(config);
        if (agent != null && !agent.isBlank()) {
            // Explicit agent beats opencode's server-side default_agent; without this the send
            // 500s when the configured default agent no longer exists ("default agent X not found").
            body.append(",\"agent\":\"").append(escapeJson(agent)).append("\"");
        }
        return body.append('}').toString();
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
            String content = joinedContent(messageId);
            List<ToolCall> stepTools = drainToolCalls(messageId);
            SessionUsage stepUsage = usageFromTokens(info.get("tokens"));
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
            if (stepUsage != null) {
                turnUsage = (turnUsage == null ? SessionUsage.EMPTY : turnUsage).add(stepUsage);
            }
            if (!content.isEmpty() || !stepTools.isEmpty() || stepUsage != null) {
                turnHasNewContent = true;
                assistantPersistedSinceSend = true;
            }
            log.info("opencode", "assistant.step-merged", "sessionId", sessionId,
                    "messageId", messageId, "chars", content.length(),
                    "toolCalls", stepTools.size());
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
                if (turnHasNewContent) {
                    String content;
                    List<ToolCall> tools = new ArrayList<>();
                    synchronized (turnTools) {
                        content = turnText.toString();
                        for (TurnTool tt : turnTools) {
                            tools.add(new ToolCall(tt.name(), tt.inputJson(), tt.output()));
                        }
                        turnTools.clear();
                    }
                    SessionUsage usage = turnUsage;
                    sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(),
                            sessionId, Role.ASSISTANT, content, tools, usage, false, clock.now()));
                    log.info("opencode", "turn.persisted", "sessionId", sessionId,
                            "chars", content.length(), "toolCalls", tools.size());
                    if (usage != null) {
                        Session latest = sessions.find(sessionId).orElse(null);
                        if (latest != null) {
                            Session updated = latest.withCumulativeUsage(latest.cumulativeUsage().add(usage));
                            sessions.update(updated);
                            writeback(updated);
                        }
                    }
                    turnText.setLength(0);
                    turnUsage = null;
                    turnHasNewContent = false;
                    assistantPersistedSinceSend = true;
                }
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
        }

        /** Upsert one tool call's latest state; reader-thread confined, ordered by first sight. */
        private void trackToolCall(String messageId, String key, String toolName,
                                   Map<String, Object> state, String output) {
            if (messageId == null || userMessages.contains(messageId)) {
                return;
            }
            LinkedHashMap<String, ToolCallState> calls =
                    toolsByMessage.computeIfAbsent(messageId, k -> new LinkedHashMap<>());
            ToolCallState st = calls.get(key);
            if (st == null) {
                st = new ToolCallState();
                calls.put(key, st);
            }
            if (toolName != null && !toolName.isBlank()) {
                st.name = toolName;
            }
            Object input = state.get("input");
            if (input != null) {
                st.argumentsJson = jsonValue(input);
            }
            if (output != null) {
                st.resultJson = output;
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

    private void ensureUpstream(String sessionId, int port, String cliSessionId) {
        Upstream up = upstreams.get(sessionId);
        if (up == null) {
            up = new Upstream(sessionId, port, cliSessionId);
            upstreams.put(sessionId, up);
            up.start();
        }
    }

    private void stopUpstream(String sessionId) {
        Upstream up = upstreams.remove(sessionId);
        if (up != null) {
            up.stop();
        }
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
     * Throws when something already answers /health on the port BEFORE we spawn our own serve —
     * an orphaned opencode process from a previous backend run. Reusing it would silently route
     * the session onto a server with stale in-memory config (e.g. a default agent that has since
     * been renamed), so the session must fail loudly instead.
     */
    private void assertPortFree(int port) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofMillis(500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "port " + port + " is already served by a stale opencode process from an "
                                + "earlier run; kill it (or adjust session.port_range in gate.toml) "
                                + "and retry");
            }
        } catch (GateException e) {
            log.error("opencode", "start.refused-stale-port", "port", port);
            throw e;
        } catch (Exception ignored) {
            // nothing listening -> port is genuinely free
        }
    }

    private void spawnServe(int port, String clonePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(opencodeExecutable, "serve",
                    "--port", String.valueOf(port), "--hostname", "127.0.0.1");
            if (clonePath != null) {
                pb.directory(Path.of(clonePath).toFile());
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            serveProcesses.put(port, p);
            pidRegistry.record(p.pid());
            log.info("opencode", "serve.spawned", "port", port,
                    "pid", p.pid(), "clonePath", clonePath);
        } catch (IOException e) {
            log.error("opencode", "serve.spawn-failed", "port", port, "error", String.valueOf(e));
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot spawn opencode serve on port " + port, e);
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

    private void postQuietly(String url, String body) {
        try {
            post(url, body);
        } catch (Exception ignored) {
            // abort best-effort
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
