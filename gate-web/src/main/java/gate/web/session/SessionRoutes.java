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
import gate.ports.SessionRepository;
import gate.ports.TicketRepository;
import gate.web.ApiRoutes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                         AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock) {
        this(agentConfigs, sessionRepository, agentSessionPort, tickets, clock, new SessionModelCatalog());
    }

    SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                  AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock,
                  SessionModelCatalog modelCatalog) {
        this.agentConfigs = agentConfigs;
        this.sessionRepository = sessionRepository;
        this.agentSessionPort = agentSessionPort;
        this.tickets = tickets;
        this.clock = clock;
        this.modelCatalog = modelCatalog;
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
        Session s = agentSessionPort.start(new AgentSessionPort.StartRequest(
                ticketNo, agentConfigId, ticket.clonePath(), ticket.targetRef(),
                initialPrompt, Map.of()));
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
        List<AgentSessionPort.Attachment> attachments = parseAttachments(req);
        if ((message == null || message.isBlank()) && attachments.isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "message is required");
        }
        // Optional per-send model/variant (会话内实时切换): persisted so the async send — and every
        // later send until changed again — uses this selection (OpenChamber per-session picker
        // semantics).
        applyModelOverrideIfPresent(sessionId, req);
        String taskId = agentSessionPort.sendMessage(
                new AgentSessionPort.SendRequest(sessionId, message == null ? "" : message, true, attachments));
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

    /** Per-send image attachment limits (粘贴图片，OpenChamber composer 同类约束). */
    static final int MAX_ATTACHMENTS = 8;
    /** Base64 payload cap per attachment (~9 MB binary). */
    static final int MAX_ATTACHMENT_BASE64_CHARS = 12_000_000;
    private static final java.util.Set<String> IMAGE_MIMES =
            java.util.Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /**
     * Optional {@code attachments: [{filename?, mime, data_base64}]} on a send. Images only —
     * non-image pastes are turned into path text by the composer before they ever reach here.
     */
    static List<AgentSessionPort.Attachment> parseAttachments(Map<String, Object> req) {
        Object raw = req.get("attachments");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "attachments must be an array");
        }
        if (list.isEmpty()) {
            return List.of();
        }
        if (list.size() > MAX_ATTACHMENTS) {
            throw new GateException(GateErrorCode.USAGE,
                    "at most " + MAX_ATTACHMENTS + " attachments per message");
        }
        List<AgentSessionPort.Attachment> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new GateException(GateErrorCode.USAGE, "each attachment must be an object");
            }
            String mime = attr(m, "mime");
            String normalizedMime = mime == null ? "" : mime.trim().toLowerCase(java.util.Locale.ROOT);
            if (!IMAGE_MIMES.contains(normalizedMime)) {
                throw new GateException(GateErrorCode.USAGE,
                        "attachment mime must be one of image/png, image/jpeg, image/gif, image/webp");
            }
            String data = attr(m, "data_base64");
            data = data == null ? "" : stripDataUrlPrefix(data.replaceAll("\\s", ""));
            if (data.isBlank()) {
                throw new GateException(GateErrorCode.USAGE, "attachment data_base64 is required");
            }
            if (data.length() > MAX_ATTACHMENT_BASE64_CHARS) {
                throw new GateException(GateErrorCode.USAGE, "attachment exceeds the size limit");
            }
            String filename = attr(m, "filename");
            out.add(new AgentSessionPort.Attachment(
                    filename == null || filename.isBlank() ? "image" : filename.trim(),
                    normalizedMime,
                    data));
        }
        return List.copyOf(out);
    }

    /** Tolerates a full {@code data:<mime>;base64,} URL — keeps only the payload. */
    private static String stripDataUrlPrefix(String value) {
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0
                && value.substring(0, comma).toLowerCase(java.util.Locale.ROOT).contains("base64")) {
            return value.substring(comma + 1);
        }
        return value;
    }

    private static String attr(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
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
            if (b && s.status() == gate.domain.session.SessionStatus.ACTIVE) {
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
