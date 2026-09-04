package gate.application.ticket;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.infra.Clock;
import gate.ports.store.TicketRepository;
import java.nio.file.Files;
import java.util.List;

/**
 * The project's quick-mode super ticket (V19 快速模式).
 *
 * <p>Every project owns exactly one system-created permanent ticket: it operates directly on the
 * project's registered workspace ({@code clone_path} IS the workspace path — no clone), commits
 * land on the workspace's own branch (主分支) by plain git, it never closes and never enters the
 * gate pipeline. {@link #ensure} is idempotent and self-healing: it creates the ticket on first
 * use (including a backfill for projects registered before V19) and re-points it whenever the
 * project's workspace path or target branch moves.
 */
public final class SuperTicketHandler {

    public static final String TITLE = "快速模式（超级工单）";
    public static final String DESCRIPTION = "系统自动创建的常驻快速模式工单：直接在项目原工作区与"
            + "Agent 交互（不克隆），提交直达主分支，永不关闭、不参与门禁流转。";

    private final TicketRepository tickets;
    private final Clock clock;

    public SuperTicketHandler(TicketRepository tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    /**
     * Returns the project's super ticket, creating it when missing and syncing its location when
     * the project moved. {@code effectiveTargetRef} is the project's resolved base branch (the
     * caller owns the request > workspace > gate-default resolution order).
     */
    public Ticket ensure(Project project, String effectiveTargetRef) {
        Ticket existing = tickets.findSuperByProject(project.id()).orElse(null);
        if (existing != null) {
            String clonePath = project.workspacePath();
            String targetRef = normalizeRef(effectiveTargetRef);
            if (!clonePath.equals(existing.clonePath()) || !targetRef.equals(existing.targetRef())) {
                tickets.updateSuperLocation(existing.ticketNo(), clonePath, targetRef, clock.now());
            }
            return tickets.find(existing.ticketNo()).orElse(existing);
        }
        return create(project, effectiveTargetRef);
    }

    private Ticket create(Project project, String effectiveTargetRef) {
        if (!Files.isDirectory(java.nio.file.Path.of(project.workspacePath()))) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "project workspace directory does not exist: " + project.workspacePath());
        }
        String ticketNo = TicketCreationHandler.nextTicketNo(tickets);
        String targetRef = normalizeRef(effectiveTargetRef);
        Ticket t = new Ticket(ticketNo, TITLE, targetRef, project.workspacePath(),
                null, null, "manual", "human", TicketStage.IN_PROGRESS, clock.now(), clock.now(),
                null, null, null, null, project.id(),
                DESCRIPTION, null, List.of("快速模式"), true);
        tickets.insert(t);
        return t;
    }

    /** Falls back to refs/heads/main for legacy NULL project rows (mirrors orMain elsewhere). */
    private static String normalizeRef(String ref) {
        return ref == null || ref.isBlank() ? "refs/heads/main" : ref;
    }
}
