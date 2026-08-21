package gate.domain.session;

import java.time.Instant;

/**
 * A single session execution (≈ multica Task) — 执行文档-后端-web §5.2.
 *
 * @param id              UUID
 * @param ticketNo        owning ticket
 * @param agentConfigId   agent config id
 * @param cli             resolved CLI
 * @param status          ACTIVE | ABORTED | CLOSED
 * @param cliSessionId    claude session-id / opencode session id
 * @param clonePath       ticket clone path
 * @param allocatedPort   opencode serve port; claude is -1
 * @param startedAt       creation time
 * @param finishedAt      nullable terminal time
 * @param cumulativeUsage cumulative token usage
 * @param title           user-facing title, nullable (workbench session list)
 * @param archived        soft-archive flag for the workbench list; not a runtime state
 */
public record Session(
        String id,
        String ticketNo,
        String agentConfigId,
        AgentCli cli,
        SessionStatus status,
        String cliSessionId,
        String clonePath,
        int allocatedPort,
        Instant startedAt,
        Instant finishedAt,
        SessionUsage cumulativeUsage,
        String title,
        boolean archived) {

    public Session {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new IllegalArgumentException("agentConfigId must not be blank");
        }
        if (cli == null) {
            throw new IllegalArgumentException("cli must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (clonePath == null || clonePath.isBlank()) {
            throw new IllegalArgumentException("clonePath must not be blank");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        cumulativeUsage = cumulativeUsage == null ? SessionUsage.EMPTY : cumulativeUsage;
    }

    public boolean isTerminal() {
        return status == SessionStatus.ABORTED || status == SessionStatus.CLOSED;
    }

    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, ticketNo, agentConfigId, cli, newStatus, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived);
    }

    public Session withFinishedAt(Instant newFinishedAt) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, newFinishedAt, cumulativeUsage, title, archived);
    }

    public Session withCliSessionId(String newCliSessionId) {
        return new Session(id, ticketNo, agentConfigId, cli, status, newCliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived);
    }

    public Session withCumulativeUsage(SessionUsage newUsage) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, newUsage, title, archived);
    }

    public Session withTitle(String newTitle) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, newTitle, archived);
    }

    public Session withArchived(boolean newArchived) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, newArchived);
    }
}
