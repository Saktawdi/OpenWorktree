package gate.web.session;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
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

    public SessionRoutes(AgentConfigRepository agentConfigs, SessionRepository sessionRepository,
                         AgentSessionPort agentSessionPort, TicketRepository tickets, Clock clock) {
        this.agentConfigs = agentConfigs;
        this.sessionRepository = sessionRepository;
        this.agentSessionPort = agentSessionPort;
        this.tickets = tickets;
        this.clock = clock;
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
        if (message == null || message.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "message is required");
        }
        String taskId = agentSessionPort.sendMessage(new AgentSessionPort.SendRequest(sessionId, message, true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        return new ApiRoutes.Response(202, body);
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
        return m;
    }

    public static Map<String, Object> sessionJson(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("ticket_no", s.ticketNo());
        m.put("agent_config_id", s.agentConfigId());
        m.put("cli", s.cli().name());
        m.put("status", s.status().name());
        m.put("cli_session_id", s.cliSessionId());
        m.put("clone_path", s.clonePath());
        m.put("allocated_port", s.allocatedPort());
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
        return new AgentConfig(id, name, AgentCli.valueOf(cli.toUpperCase(java.util.Locale.ROOT)),
                providerId, model, str(req, "system_prompt"), extraFlags, str(req, "description"));
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
