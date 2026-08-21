package gate.adapters.session;

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

import java.io.IOException;
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
import java.util.stream.Stream;

/**
 * OpenCode serve adapter (执行文档-后端-web §5.3.1, ADR-12): talks to a local
 * {@code opencode serve} HTTP API on an allocated port.
 *
 * <p>When {@code opencodeExecutable} is non-blank the adapter spawns the serve process; tests pass a
 * blank executable and point at a fake HTTP server on the allocated port.
 */
public final class OpenCodeServeAdapter implements AgentSessionPort {

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
    private final Map<Integer, Process> serveProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionPorts = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<java.util.function.Consumer<SessionStreamChunk>>> listeners = new ConcurrentHashMap<>();

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
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "opencode-session");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public AutoCloseable attachListener(String sessionId, java.util.function.Consumer<SessionStreamChunk> listener) {
        listeners.computeIfAbsent(sessionId, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>())).add(listener);
        return () -> {
            java.util.Set<java.util.function.Consumer<SessionStreamChunk>> set = listeners.get(sessionId);
            if (set != null) {
                set.remove(listener);
            }
        };
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
        for (Integer port : List.copyOf(sessionPorts.values())) {
            killProcess(port);
            ports.release(port);
        }
        sessionPorts.clear();
        executor.shutdown();
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

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
        } catch (IOException e) {
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

    private void runSend(GateTask task, Session session, String message) {
        try (AutoCloseable ignored = ticketLocks.acquire(session.ticketNo())) {
            Integer port = sessionPorts.get(session.id());
            if (port == null || session.cliSessionId() == null) {
                throw new GateException(GateErrorCode.USAGE, "session has no opencode endpoint");
            }
            AgentConfig config = agentConfigs.find(session.agentConfigId()).orElseThrow();
            tasks.update(progress(task, 10, "发送到 opencode"));
            String body = messageBody(config, message);
            HttpResponse<String> resp = post("http://127.0.0.1:" + port + "/session/"
                    + session.cliSessionId() + "/message", body);
            tasks.update(progress(task, 70, "解析 opencode 响应"));
            ParsedOutput parsed = parseResponse(resp.body());
            Instant now = clock.now();
            if (parsed.text != null || parsed.usage != null) {
                sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                        Role.ASSISTANT, parsed.text == null ? "" : parsed.text, List.of(),
                        parsed.usage, parsed.degraded, now));
                if (parsed.text != null) {
                    emitChunk(session.id(), new SessionStreamChunk.ContentChunk(session.id(), parsed.text, now));
                }
                if (parsed.usage != null) {
                    emitChunk(session.id(), new SessionStreamChunk.UsageChunk(session.id(), parsed.usage, now));
                }
            } else {
                sessions.insertMessage(new SessionMessage(UUID.randomUUID().toString(), session.id(),
                        Role.ERROR, resp.body(), List.of(), null, true, now));
                emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "OPENCODE_ERROR", resp.body(), now));
            }
            SessionUsage cumulative = session.cumulativeUsage().add(
                    parsed.usage == null ? SessionUsage.EMPTY : parsed.usage);
            Session updated = session.withCumulativeUsage(cumulative);
            sessions.update(updated);
            writeback(updated);
            emitChunk(session.id(), new SessionStreamChunk.DoneChunk(session.id(), session.id(), now));
            tasks.update(success(task, "{\"message_count\":" + sessions.findMessages(session.id()).size() + "}"));
        } catch (Throwable e) {
            emitChunk(session.id(), new SessionStreamChunk.ErrorChunk(session.id(), "INTERNAL_ERROR", e.getMessage(), clock.now()));
            tasks.update(fail(task, e));
        }
    }

    private static String messageBody(AgentConfig config, String message) {
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
        return body.append('}').toString();
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

    private HttpResponse<String> post(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
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

    @SuppressWarnings("unchecked")
    private static ParsedOutput parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new ParsedOutput(null, null, true);
        }
        try {
            Object parsed = MiniJson.parse(responseBody.trim());
            if (!(parsed instanceof Map<?, ?> m)) {
                return new ParsedOutput(null, null, false);
            }
            Map<String, Object> obj = (Map<String, Object>) m;
            String text = extractText(obj.get("message"), obj.get("text"), obj.get("content"));
            if (text == null && obj.get("parts") instanceof List<?> partsList) {
                StringBuilder sb = new StringBuilder();
                for (Object p : partsList) {
                    if (p instanceof Map<?, ?> pm) {
                        Map<String, Object> pmm = (Map<String, Object>) pm;
                        Object type = pmm.get("type");
                        if ("text".equals(String.valueOf(type))) {
                            Object t = pmm.get("text");
                            if (t != null) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(t);
                            }
                        }
                    }
                }
                if (sb.length() > 0) {
                    text = sb.toString();
                }
            }
            SessionUsage usage = null;
            Object usageObj = obj.get("usage");
            if (usageObj == null && obj.get("info") instanceof Map<?, ?> infoMap) {
                usageObj = ((Map<?, ?>) infoMap).get("tokens");
            }
            if (usageObj == null && obj.get("parts") instanceof List<?> partsList) {
                for (Object p : partsList) {
                    if (p instanceof Map<?, ?> pm) {
                        Map<String, Object> pmm = (Map<String, Object>) pm;
                        if (pmm.get("tokens") instanceof Map<?, ?>) {
                            usageObj = pmm.get("tokens");
                            break;
                        }
                    }
                }
            }
            if (usageObj instanceof Map<?, ?> um) {
                Map<String, Object> usageMap = (Map<String, Object>) um;
                Long prompt = longOrNull(usageMap.get("input_tokens"));
                if (prompt == null) {
                    prompt = longOrNull(usageMap.get("input"));
                }
                if (prompt == null) {
                    prompt = longOrNull(usageMap.get("prompt_tokens"));
                }
                Long completion = longOrNull(usageMap.get("output_tokens"));
                if (completion == null) {
                    completion = longOrNull(usageMap.get("output"));
                }
                if (completion == null) {
                    completion = longOrNull(usageMap.get("completion_tokens"));
                }
                Long total = longOrNull(usageMap.get("total_tokens"));
                if (total == null) {
                    total = longOrNull(usageMap.get("total"));
                }
                if (prompt != null || completion != null || total != null) {
                    usage = new SessionUsage(prompt, completion, total);
                }
            }
            boolean degraded = usage == null || (text == null && (responseBody.contains("error") || responseBody.contains("FAIL")));
            return new ParsedOutput(text, usage, degraded);
        } catch (Throwable e) {
            System.err.println("Failed to parse opencode response: " + e.getMessage());
            e.printStackTrace();
            return new ParsedOutput(null, null, true);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Object message, Object directText, Object directContent) {
        if (directText != null) {
            return String.valueOf(directText);
        }
        if (directContent instanceof String s) {
            return s;
        }
        if (message instanceof Map<?, ?> m) {
            Map<String, Object> mm = (Map<String, Object>) m;
            Object content = mm.get("content");
            if (content instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> cm) {
                        Map<String, Object> cmm = (Map<String, Object>) cm;
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
            Object text = mm.get("text");
            if (text != null) {
                return String.valueOf(text);
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

    private record ParsedOutput(String text, SessionUsage usage, boolean degraded) {
    }
}
