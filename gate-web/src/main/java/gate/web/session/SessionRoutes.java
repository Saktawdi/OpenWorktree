package gate.web.session;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.PermissionRequest;
import gate.domain.session.Session;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.Clock;
import gate.ports.CredentialRepository;
import gate.ports.SessionRepository;
import gate.ports.TicketRepository;
import gate.web.ApiRoutes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Session capability handler (EX-001 Phase 1).
 * Owns /api/agent-configs, /api/sessions and /api/tickets/{no}/sessions routes.
 * Extracted from ApiRoutes to satisfy GOV-CPLX-001 and capability-registry.
 */
public final class SessionRoutes {
    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessionRepository;
    private final AgentSessionPort agentSessionPort;
    private final TicketRepository tickets;
    private final Clock clock;
    private final SessionModelCatalog modelCatalog;
    /**
     * Mints the ticket-bound agent-domain token handed to the CLI adapter's MCP provisioning.
     * Null (tests) = sessions start without gate MCP tools, as before this capability existed.
     */
    private final CredentialRepository credentials;

    public SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                         AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock) {
        this(agentConfigs, sessionRepository, agentSessionPort, tickets, clock, new SessionModelCatalog());
    }

    public SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                         AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock,
                         SessionModelCatalog modelCatalog) {
        this(agentConfigs, sessionRepository, agentSessionPort, tickets, clock, modelCatalog, null);
    }

    public SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                         AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock,
                         SessionModelCatalog modelCatalog, CredentialRepository credentials) {
        this.agentConfigs = agentConfigs;
        this.sessionRepository = sessionRepository;
        this.agentSessionPort = agentSessionPort;
        this.tickets = tickets;
        this.clock = clock;
        this.modelCatalog = modelCatalog;
        this.credentials = credentials;
    }

    public ApiRoutes.Response agentConfigList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentConfig c : agentConfigs.findAll()) {
            out.add(agentConfigJson(c));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_configs", out);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response agentConfigDetail(String id) {
        AgentConfig c = agentConfigs.find(id).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such agent config: " + id));
        return new ApiRoutes.Response(200, agentConfigJson(c));
    }

    public ApiRoutes.Response agentConfigCreate(String requestBody) {
        AgentConfig c = parseAgentConfig(requestBody, null);
        if (agentConfigs.find(c.id()).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "agent config already exists: " + c.id());
        }
        agentConfigs.insert(c, clock.now());
        return new ApiRoutes.Response(201, agentConfigJson(c));
    }

    public ApiRoutes.Response agentConfigUpdate(String id, String requestBody) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        AgentConfig c = parseAgentConfig(requestBody, id);
        agentConfigs.update(c, clock.now());
        return new ApiRoutes.Response(200, agentConfigJson(agentConfigs.find(id).orElseThrow()));
    }

    public ApiRoutes.Response agentConfigDelete(String id) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        agentConfigs.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response agentConfigSessions(String id) {
        if (agentConfigs.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such agent config: " + id);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByAgentConfig(id)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response ticketSessions(String ticketNo) {
        if (tickets.find(ticketNo).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Session s : sessionRepository.findByTicket(ticketNo)) {
            out.add(sessionJson(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response sessionCreate(String ticketNo, String requestBody) {
        gate.domain.ticket.Ticket ticket = tickets.find(ticketNo).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such ticket: " + ticketNo));
        Map<String, Object> req = parseObject(requestBody);
        String agentConfigId = str(req, "agent_config_id");
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent_config_id is required");
        }
        String initialPrompt = str(req, "initial_prompt");
        if (initialPrompt == null) {
            initialPrompt = "";
        }
        // Every session gets a freshly minted agent-domain token bound to this ticket (§5.4):
        // the plaintext rides only inside StartRequest.env → the CLI process tree / per-session
        // MCP config under the clone's .git/, and only its hash is persisted. Without it the
        // agent CLI cannot bring up the gate MCP server and presubmit_create stays missing
        // (the T-110 session: "仅有 Open Design 相关（无 presubmit_create）").
        Map<String, String> startEnv = credentials == null
                ? Map.of()
                : Map.of(gate.adapters.mcp.McpServer.TOKEN_ENV,
                        credentials.issueAgentToken(ticketNo, clock.now()));
        Session s = agentSessionPort.start(new AgentSessionPort.StartRequest(
                ticketNo, agentConfigId, ticket.clonePath(), ticket.targetRef(),
                initialPrompt, startEnv));
        return new ApiRoutes.Response(201, sessionJson(s));
    }

    public ApiRoutes.Response sessionDetail(String sessionId) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        return new ApiRoutes.Response(200, sessionJson(s));
    }

    public ApiRoutes.Response sessionHistory(String sessionId) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (gate.domain.session.SessionMessage m : sessionRepository.findMessages(sessionId)) {
            out.add(sessionMessageJson(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", out);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response sessionSend(String sessionId, String requestBody) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        Map<String, Object> req = parseObject(requestBody);
        String message = str(req, "message");
        if (message == null || message.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "message is required");
        }
        // Optional per-send model/variant (会话内实时切换): persisted so the async send — and every
        // later send until changed again — uses this selection (OpenChamber per-session picker
        // semantics).
        applyModelOverrideIfPresent(sessionId, req);
        String taskId = agentSessionPort.sendMessage(new AgentSessionPort.SendRequest(sessionId, message, true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new ApiRoutes.Response(202, body);
    }

    /**
     * POST /api/sessions/{id}/model — live model / reasoning-effort switch. Body:
     * {@code {"provider_id"?, "model_id"?, "variant"?}}. Per-key tri-state semantics: a missing
     * key keeps the current value, a present blank value clears it back to the AgentConfig
     * default, and a present pair sets the override. Takes effect on the NEXT send; safe to call
     * mid-turn.
     */
    public ApiRoutes.Response sessionModelSet(String sessionId, String requestBody) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        Map<String, Object> req = parseObject(requestBody);
        Session updated = s.withModelOverride(
                mergeOverridePart(s.overrideProvider(), req, "provider_id"),
                mergeOverridePart(s.overrideModel(), req, "model_id"),
                mergeOverridePart(s.overrideVariant(), req, "variant"));
        if (updated.overrideProvider() != null && updated.overrideModel() == null
                || updated.overrideProvider() == null && updated.overrideModel() != null) {
            throw new GateException(GateErrorCode.USAGE,
                    "provider_id and model_id must be provided together");
        }
        sessionRepository.update(updated);
        return new ApiRoutes.Response(200, sessionJson(updated));
    }

    /**
     * GET /api/sessions/{id}/models — live catalog from the session's opencode serve
     * ({@code /config/providers}, reduced for the picker): providers → models → variant keys.
     */
    public ApiRoutes.Response sessionModels(String sessionId) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        return new ApiRoutes.Response(200, modelCatalog.fetch(s.allocatedPort()));
    }

    private void applyModelOverrideIfPresent(String sessionId, Map<String, Object> req) {
        if (!req.containsKey("provider_id") && !req.containsKey("model_id")
                && !req.containsKey("variant")) {
            return;
        }
        Session s = sessionRepository.find(sessionId).orElseThrow();
        Session updated = s.withModelOverride(
                mergeOverridePart(s.overrideProvider(), req, "provider_id"),
                mergeOverridePart(s.overrideModel(), req, "model_id"),
                mergeOverridePart(s.overrideVariant(), req, "variant"));
        sessionRepository.update(updated);
    }

    /**
     * Tri-state per-key merge: absent key keeps {@code current}; present blank clears (null);
     * present non-blank sets the trimmed value.
     */
    private static String mergeOverridePart(String current, Map<String, Object> req, String key) {
        if (!req.containsKey(key)) {
            return current;
        }
        String value = str(req, key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ApiRoutes.Response sessionAbort(String sessionId) {
        if (sessionRepository.find(sessionId).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such session: " + sessionId);
        }
        agentSessionPort.abort(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new ApiRoutes.Response(200, body);
    }

    /**
     * PATCH /api/sessions/{id} — workbench session-list metadata. Body may carry {@code title}
     * (string; empty string clears it to null) and/or {@code archived} (boolean); at least one
     * of the two is required.
     */
    public ApiRoutes.Response sessionPatch(String sessionId, String requestBody) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        Map<String, Object> req = parseObject(requestBody);
        if (!req.containsKey("title") && !req.containsKey("archived")
                && !req.containsKey("permission_auto_accept")) {
            throw new GateException(GateErrorCode.USAGE,
                    "at least one of title/archived/permission_auto_accept is required");
        }
        Session updated = s;
        // Archived is processed before title so the post-abort re-fetch (which refreshes
        // status/finishedAt) cannot clobber other fields mutated in this request.
        if (req.containsKey("archived")) {
            Object archived = req.get("archived");
            if (!(archived instanceof Boolean b)) {
                throw new GateException(GateErrorCode.USAGE, "archived must be a boolean");
            }
            // Archiving releases the session's runtime resources: the serve process and the
            // upstream event reader would otherwise outlive the visible session forever.
            // Pre-mark ABORTED so the opencode adapter hard-kills the serve instead of the
            // soft turn-abort an interactive stop uses.
            if (b && s.status() == gate.domain.session.SessionStatus.ACTIVE) {
                sessionRepository.update(s.withStatus(gate.domain.session.SessionStatus.ABORTED)
                        .withFinishedAt(clock.now()));
                agentSessionPort.abort(sessionId);
                updated = sessionRepository.find(sessionId).orElse(s);
            }
            updated = updated.withArchived(b);
        }
        if (req.containsKey("title")) {
            String title = str(req, "title");
            updated = updated.withTitle(title == null || title.isBlank() ? null : title);
        }
        if (req.containsKey("permission_auto_accept")) {
            Object auto = req.get("permission_auto_accept");
            if (!(auto instanceof Boolean b)) {
                throw new GateException(GateErrorCode.USAGE, "permission_auto_accept must be a boolean");
            }
            updated = updated.withPermissionAutoAccept(b);
        }
        sessionRepository.update(updated);
        return new ApiRoutes.Response(200, sessionJson(updated));
    }

    /**
     * POST /api/sessions/{id}/permissions/{permissionId} — answer an opencode permission.asked.
     * Body: {@code {"response":"once"|"always"|"reject"}}. Only opencode sessions can carry
     * permission requests; anything else is rejected before reaching the port.
     */
    public ApiRoutes.Response sessionPermissionRespond(String sessionId, String permissionId, String requestBody) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        if (s.cli() != AgentCli.OPENCODE) {
            throw new GateException(GateErrorCode.USAGE,
                    "permission asks are only supported for opencode sessions");
        }
        Map<String, Object> req = parseObject(requestBody);
        String response = str(req, "response");
        if (response == null || !(response.equals("once") || response.equals("always") || response.equals("reject"))) {
            throw new GateException(GateErrorCode.USAGE, "response must be one of once/always/reject");
        }
        agentSessionPort.respondPermission(sessionId, permissionId, response);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("permission_id", permissionId);
        body.put("response", response);
        return new ApiRoutes.Response(200, body);
    }

    /** GET /api/agents/busy — 顶栏运行中智能体计数（有进行中回合的会话快照）。 */
    public ApiRoutes.Response agentsBusy() {
        // 有进行中回合的 session id 快照来源于各 adapter 的 in-flight registry，聚合并排序
        Set<String> ids = agentSessionPort.busySessionIds();
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        List<Map<String, Object>> running = new ArrayList<>();
        for (String sid : sorted) {
            Optional<Session> opt = sessionRepository.find(sid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", sid);
            if (opt.isPresent()) {
                Session s = opt.get();
                m.put("title", s.title());
                m.put("ticket_no", s.ticketNo());
                m.put("cli", s.cli() == null ? null : s.cli().name());
            } else {
                // 查不到会话记录的 id 仍计入 count 并保留 session_id，其余字段为 null
                m.put("title", null);
                m.put("ticket_no", null);
                m.put("cli", null);
            }
            running.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", running.size());
        body.put("running", running);
        return new ApiRoutes.Response(200, body);
    }

    /** GET /api/sessions/{id}/permissions — unresolved pending permission asks for this session. */
    public ApiRoutes.Response permissionList(String sessionId) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        List<Map<String, Object>> perms = new ArrayList<>();
        for (PermissionRequest p : agentSessionPort.pendingPermissions(sessionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", sessionId);
            m.put("timestamp", clock.now().toString());
            m.put("permission_id", p.permissionId());
            m.put("permission", p.permission());
            m.put("patterns", p.patterns());
            m.put("always", p.always());
            m.put("metadata", p.metadata());
            m.put("message_id", p.messageId());
            m.put("call_id", p.callId());
            perms.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", perms);
        return new ApiRoutes.Response(200, body);
    }

    /** DELETE /api/sessions/{id} — drops the session and its messages; always aborts first. */
    public ApiRoutes.Response sessionDelete(String sessionId) {
        Session s = sessionRepository.find(sessionId).orElseThrow(() -> new GateException(
                GateErrorCode.USAGE, "no such session: " + sessionId));
        // Abort unconditionally: even a non-ACTIVE row may still own an upstream reader or a
        // serve process after edge cases (e.g. an abort that raced a status flip).
        // Pre-mark ABORTED so the opencode adapter takes the hard path (kill serve) — a soft
        // abort would leave the serve process alive under a deleted session row.
        if (s.status() != gate.domain.session.SessionStatus.ABORTED) {
            sessionRepository.update(s.withStatus(gate.domain.session.SessionStatus.ABORTED)
                    .withFinishedAt(clock.now()));
        }
        agentSessionPort.abort(sessionId);
        sessionRepository.deleteMessages(sessionId);
        sessionRepository.delete(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return new ApiRoutes.Response(200, body);
    }

    public static Map<String, Object> agentConfigJson(AgentConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("cli", c.cli().name());
        m.put("provider_id", c.providerId());
        m.put("model", c.model());
        m.put("system_prompt", c.systemPrompt());
        m.put("extra_flags", c.extraFlags());
        m.put("description", c.description());
        m.put("inject_context", c.injectContext());
        return m;
    }

    public static Map<String, Object> sessionJson(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ticket_no", s.ticketNo());
        m.put("agent_config_id", s.agentConfigId());
        m.put("cli", s.cli().name());
        m.put("status", s.status().name());
        m.put("title", s.title());
        m.put("archived", s.archived());
        m.put("cli_session_id", s.cliSessionId());
        m.put("clone_path", s.clonePath());
        m.put("allocated_port", s.allocatedPort());
        m.put("override_provider", s.overrideProvider());
        m.put("override_model", s.overrideModel());
        m.put("override_variant", s.overrideVariant());
        m.put("permission_auto_accept", s.permissionAutoAccept());
        m.put("started_at", s.startedAt().toString());
        m.put("finished_at", s.finishedAt() == null ? null : s.finishedAt().toString());
        if (s.cumulativeUsage() == null) {
            m.put("cumulative_usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", s.cumulativeUsage().promptTokens());
            u.put("completion_tokens", s.cumulativeUsage().completionTokens());
            u.put("total_tokens", s.cumulativeUsage().totalTokens());
            m.put("cumulative_usage", u);
        }
        return m;
    }

    public static Map<String, Object> sessionMessageJson(gate.domain.session.SessionMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("session_id", m.sessionId());
        out.put("role", m.role().name());
        out.put("content", m.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (gate.domain.session.ToolCall tc : m.toolCalls()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", tc.name());
            cm.put("arguments_json", tc.argumentsJson());
            cm.put("result_json", tc.resultJson());
            calls.add(cm);
        }
        out.put("tool_calls", calls);
        if (m.usage() == null) {
            out.put("usage", null);
        } else {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("prompt_tokens", m.usage().promptTokens());
            u.put("completion_tokens", m.usage().completionTokens());
            u.put("total_tokens", m.usage().totalTokens());
            out.put("usage", u);
        }
        out.put("degraded", m.degraded());
        out.put("timestamp", m.timestamp().toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static AgentConfig parseAgentConfig(String requestBody, String idOverride) {
        Map<String, Object> req = parseObject(requestBody);
        String id = idOverride != null ? idOverride : str(req, "id");
        if (id == null || id.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config id is required");
        }
        String name = str(req, "name");
        if (name == null || name.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config name is required");
        }
        String cli = str(req, "cli");
        if (cli == null || cli.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "agent config cli is required");
        }
        String providerId = str(req, "provider_id");
        String model = str(req, "model");
        List<String> extraFlags = new ArrayList<>();
        Object flags = req.get("extra_flags");
        if (flags instanceof List<?> list) {
            for (Object o : list) {
                extraFlags.add(String.valueOf(o));
            }
        }
        // 缺省注入：请求未携带 inject_context 时视为开启（与 UI 开关默认值一致）。
        Object injectRaw = req.get("inject_context");
        boolean injectContext = !(injectRaw instanceof Boolean b) || b;
        return new AgentConfig(id, name, AgentCli.valueOf(cli.toUpperCase(java.util.Locale.ROOT)),
                providerId, model, str(req, "system_prompt"), extraFlags, str(req, "description"),
                injectContext);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = gate.application.MiniJson.parse(body.trim());
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception e) {
            throw new GateException(GateErrorCode.USAGE, "malformed JSON body");
        }
        throw new GateException(GateErrorCode.USAGE, "request body must be a JSON object");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
