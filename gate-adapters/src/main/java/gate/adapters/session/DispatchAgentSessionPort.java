package gate.adapters.session;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.ports.AgentConfigRepository;
import gate.ports.AgentSessionPort;
import gate.ports.SessionRepository;
import java.util.List;
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
