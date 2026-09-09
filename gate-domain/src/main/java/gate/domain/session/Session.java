package gate.domain.session;

import java.time.Instant;

/**
 * A single session execution (≈ multica Task) — 执行文档-后端-web §5.2.
 *
 * @param id                   UUID
 * @param ticketNo             owning ticket
 * @param agentConfigId        agent config id
 * @param cli                  resolved CLI
 * @param status               ACTIVE | ABORTED | CLOSED
 * @param cliSessionId         claude session-id / opencode session id
 * @param clonePath            ticket clone path
 * @param allocatedPort        opencode serve port; claude is -1
 * @param startedAt            creation time
 * @param finishedAt           nullable terminal time
 * @param cumulativeUsage      cumulative token usage
 * @param title                user-facing title, nullable (workbench session list)
 * @param archived             soft-archive flag for the workbench list; not a runtime state
 * @param overrideProvider     live model-switch override provider id (nullable; null = AgentConfig default)
 * @param overrideModel        live model-switch override model id (nullable)
 * @param overrideVariant      live reasoning-effort variant (nullable; OpenCode prompt "variant")
 * @param permissionAutoAccept per-session auto-allow switch: opencode permission.asked is
 *                             answered "once" by the server instead of waiting for the user
 * @param permissionMode      claude-only permission mode (acceptEdits/plan/auto/
 *                            bypassPermissions); null = fall back to acceptEdits.
 *                            opencode sessions never read this column.
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
        boolean archived,
        String overrideProvider,
        String overrideModel,
        String overrideVariant,
        boolean permissionAutoAccept,
        String permissionMode) {

    /** Legacy shape (pre model-override/permission-auto-accept); overrides default to unset, auto-accept off. */
    public Session(String id, String ticketNo, String agentConfigId, AgentCli cli,
                   SessionStatus status, String cliSessionId, String clonePath, int allocatedPort,
                   Instant startedAt, Instant finishedAt, SessionUsage cumulativeUsage,
                   String title, boolean archived) {
        this(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath, allocatedPort,
                startedAt, finishedAt, cumulativeUsage, title, archived, null, null, null, false, null);
    }

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
        overrideProvider = nullable(overrideProvider);
        overrideModel = nullable(overrideModel);
        overrideVariant = nullable(overrideVariant);
        permissionMode = nullable(permissionMode);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public boolean isTerminal() {
        return status == SessionStatus.ABORTED || status == SessionStatus.CLOSED;
    }

    public Session withStatus(SessionStatus newStatus) {
        return new Session(id, ticketNo, agentConfigId, cli, newStatus, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    public Session withFinishedAt(Instant newFinishedAt) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, newFinishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    public Session withCliSessionId(String newCliSessionId) {
        return new Session(id, ticketNo, agentConfigId, cli, status, newCliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    /** 懒复活时把重建的 opencode serve 端口写回会话行。 */
    public Session withAllocatedPort(int newAllocatedPort) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                newAllocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    public Session withCumulativeUsage(SessionUsage newUsage) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, newUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    public Session withTitle(String newTitle) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, newTitle, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    public Session withArchived(boolean newArchived) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, newArchived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept, permissionMode);
    }

    /**
     * Live model/effort switch (会话内实时切换): persists the provider/model pair and the
     * reasoning-effort variant used by the NEXT send. Any component may be null to fall back
     * to the AgentConfig defaults.
     */
    public Session withModelOverride(String provider, String model, String variant) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                nullable(provider), nullable(model), nullable(variant), permissionAutoAccept, permissionMode);
    }

    /**
     * Live per-session auto-allow toggle (权限卡片自动允许): when on, the adapter answers each
     * opencode permission.asked with "once" on the server's behalf.
     */
    public Session withPermissionAutoAccept(boolean newPermissionAutoAccept) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, newPermissionAutoAccept, permissionMode);
    }

    /**
     * Live claude permission-mode switch (权限模式轮询): persists the mode the NEXT send's
     * buildArgv pins to --permission-mode. Null falls back to acceptEdits.
     */
    public Session withPermissionMode(String newPermissionMode) {
        return new Session(id, ticketNo, agentConfigId, cli, status, cliSessionId, clonePath,
                allocatedPort, startedAt, finishedAt, cumulativeUsage, title, archived,
                overrideProvider, overrideModel, overrideVariant, permissionAutoAccept,
                nullable(newPermissionMode));
    }
}
