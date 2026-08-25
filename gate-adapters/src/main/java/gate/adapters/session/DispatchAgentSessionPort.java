package gate.adapters.session;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.PermissionRequest;
import gate.ports.store.AgentConfigRepository;
import gate.ports.session.AgentSessionPort;
import gate.ports.store.SessionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dispatches {@link AgentSessionPort} calls to the adapter matching the {@link AgentCli} (ADR-12).
 */
public final class DispatchAgentSessionPort implements AgentSessionPort {

    private final AgentConfigRepository agentConfigs;
    private final SessionRepository sessions;
    private final AgentSessionPort claude;
    private final AgentSessionPort opencode;

    public DispatchAgentSessionPort(AgentConfigRepository agentConfigs,
                                    SessionRepository sessions,
                                    AgentSessionPort claude,
                                    AgentSessionPort opencode) {
        this.agentConfigs = agentConfigs;
        this.sessions = sessions;
        this.claude = claude;
        this.opencode = opencode;
    }

    @Override
    public Session start(StartRequest request) {
        AgentConfig config = agentConfigs.find(request.agentConfigId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such agent config: " + request.agentConfigId()));
        return adapter(config.cli()).start(request);
    }

    @Override
    public String sendMessage(SendRequest request) {
        Session s = sessions.find(request.sessionId())
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + request.sessionId()));
        return adapter(s.cli()).sendMessage(request);
    }

    @Override
    public void abort(String sessionId) {
        sessions.find(sessionId).ifPresent(s -> adapter(s.cli()).abort(sessionId));
    }

    @Override
    public List<SessionMessage> getHistory(String sessionId) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        return adapter(s.cli()).getHistory(sessionId);
    }

    @Override
    public Stream<SessionEvent> streamEvents(String sessionId) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        return adapter(s.cli()).streamEvents(sessionId);
    }

    @Override
    public AutoCloseable attachListener(String sessionId, java.util.function.Consumer<gate.domain.session.SessionStreamChunk> listener) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        return adapter(s.cli()).attachListener(sessionId, listener);
    }

    @Override
    public void respondPermission(String sessionId, String permissionId, String response) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        adapter(s.cli()).respondPermission(sessionId, permissionId, response);
    }

    @Override
    public List<PermissionRequest> pendingPermissions(String sessionId) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        return adapter(s.cli()).pendingPermissions(sessionId);
    }

    @Override
    public List<gate.domain.session.QuestionRequest> pendingQuestions(String sessionId) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        return adapter(s.cli()).pendingQuestions(sessionId);
    }

    @Override
    public void respondQuestion(String sessionId, String requestId, List<List<String>> answers) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        adapter(s.cli()).respondQuestion(sessionId, requestId, answers);
    }

    @Override
    public void rejectQuestion(String sessionId, String requestId) {
        Session s = sessions.find(sessionId)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE,
                        "no such session: " + sessionId));
        adapter(s.cli()).rejectQuestion(sessionId, requestId);
    }

    @Override
    public Set<String> busySessionIds() {
        // 聚合两个适配器的并集，排序后返回不可变快照，输出稳定便于测试
        Set<String> merged = new LinkedHashSet<>();
        if (claude != null) {
            merged.addAll(claude.busySessionIds());
        }
        if (opencode != null) {
            merged.addAll(opencode.busySessionIds());
        }
        List<String> sorted = new ArrayList<>(merged);
        Collections.sort(sorted);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private AgentSessionPort adapter(AgentCli cli) {
        if (cli == AgentCli.OPENCODE) {
            if (opencode == null) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "opencode adapter is not wired in this build");
            }
            return opencode;
        }
        return claude;
    }
}
