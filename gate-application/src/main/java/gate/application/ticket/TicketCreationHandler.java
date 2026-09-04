package gate.application.ticket;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.domain.project.Project;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.git.TopologyInitializer;
import gate.ports.infra.Clock;
import gate.ports.store.ProjectRepository;
import gate.ports.store.TicketRepository;
import gate.application.project.ProjectAuthResolver;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates a ticket: validates the request, cuts the ticket branch from the topology's primary ref
 * (server-side, same bootstrap nature as the seed) and materializes the independent clone.
 *
 * <p>One creation path serves both the web console and the agent-facing MCP {@code ticket_create}
 * tool, so the validation rules (stage/priority/labels/target-branch) and the auto numbering
 * (next {@code T-nnn}, base 101) cannot drift between the two entry points.
 *
 * <p>Project resolution follows {@link ProjectAuthResolver#forNewTicket}: a bound project clones
 * from its own auth repo (the T-107 fix), everything else falls back to the gate-level topology.
 * {@code projects} may be null in legacy wirings, which then only supports unaffiliated tickets.
 */
public final class TicketCreationHandler {

    private static final Pattern SINGLE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private final TicketRepository tickets;
    private final ProjectRepository projects;
    private final GateConfig config;
    private final TopologyInitializer topologyInitializer;
    private final Clock clock;

    public TicketCreationHandler(TicketRepository tickets, ProjectRepository projects, GateConfig config,
                                 TopologyInitializer topologyInitializer, Clock clock) {
        this.tickets = tickets;
        this.projects = projects;
        this.config = config;
        this.topologyInitializer = topologyInitializer;
        this.clock = clock;
    }

    public Ticket handle(CreateTicketCommand command) {
        if (topologyInitializer == null) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "ticket creation is not wired into this GateService instance");
        }
        String requestedNo = normalize(command.ticketNo());
        String title = command.title() == null ? "" : command.title();
        String ticketNo = requestedNo != null ? requestedNo : generateTicketNo();
        if (tickets.find(ticketNo).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "ticket already exists: " + ticketNo);
        }
        TicketStage stage = TicketStage.IN_PROGRESS;
        if (command.stage() != null) {
            TicketStage parsed = parseStage(command.stage());
            if (parsed != TicketStage.PENDING && parsed != TicketStage.IN_PROGRESS) {
                throw new GateException(GateErrorCode.USAGE,
                        "new ticket stage must be PENDING or IN_PROGRESS, got " + command.stage());
            }
            stage = parsed;
        }
        String priority = parsePriority(command.priority());
        String description = normalize(command.description());
        String note = normalize(command.note());
        List<String> labels = parseLabels(command.labels());
        String projectId = normalize(command.projectId());
        Project project = null;
        if (projectId != null) {
            if (projects == null) {
                throw new GateException(GateErrorCode.USAGE,
                        "projects are not wired into this GateService instance; "
                                + "tickets cannot be bound to a project here");
            }
            final String pid = projectId;
            project = projects.find(pid).orElseThrow(() -> new GateException(
                    GateErrorCode.USAGE, "no such project: " + pid));
        }
        String agentConfigId = normalize(command.agentConfigId());

        // Project tickets clone from the project's own auth repo; only unaffiliated tickets use
        // the gate-level topology (ProjectAuthResolver — cross-project clones caused T-107).
        var topology = new ProjectAuthResolver(projects, config).forNewTicket(project);
        String primaryRef = topology.targetRef();
        String targetRef = resolveTargetRef(command.targetBranch(), ticketNo);
        RepoRef auth = topology.authRepo();
        if (!Files.exists(auth.path())) {
            throw new GateException(GateErrorCode.USAGE,
                    "auth repo for this ticket does not exist: " + auth.pathString()
                            + " (init it before creating tickets)");
        }
        if (!targetRef.equals(primaryRef)) {
            topologyInitializer.ensureBranch(auth, targetRef, primaryRef);
        }
        var clone = topologyInitializer.createClone(auth, targetRef,
                config.clonesRoot().resolve(ticketNo));

        Ticket t = new Ticket(ticketNo, title, targetRef, clone.pathString(),
                null, null, "manual", "human", stage, clock.now(), clock.now(),
                null, null, agentConfigId, priority, projectId,
                description, note, labels);
        tickets.insert(t);
        return t;
    }

    /** Next free {@code T-nnn}: one past the highest existing number, never below 101. */
    private String generateTicketNo() {
        return nextTicketNo(tickets);
    }

    /** Shared numbering pool for regular tickets and the quick-mode super ticket (V19). */
    public static String nextTicketNo(TicketRepository tickets) {
        Pattern numbered = Pattern.compile("T-(\\d+)");
        int next = 101;
        for (Ticket t : tickets.findAll()) {
            java.util.regex.Matcher m = numbered.matcher(t.ticketNo());
            if (m.matches()) {
                next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
            }
        }
        while (tickets.find("T-" + next).isPresent()) {
            next++;
        }
        return "T-" + next;
    }

    /**
     * The ticket's own branch: the requested branch when given (single segment, no
     * trailing ".lock"), else the ticket number — mirroring the web controller's rules.
     */
    private static String resolveTargetRef(String requestedBranch, String ticketNo) {
        String name;
        if (requestedBranch == null || requestedBranch.isBlank()) {
            name = ticketNo;
        } else {
            name = requestedBranch.trim();
            if (name.startsWith("refs/heads/")) {
                name = name.substring("refs/heads/".length());
            }
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".lock")
                || !SINGLE_SEGMENT.matcher(name).matches() || name.length() > 80) {
            throw new GateException(GateErrorCode.USAGE,
                    "target_branch must match [A-Za-z0-9._-]+ (single segment, no slash): "
                            + requestedBranch);
        }
        return "refs/heads/" + name;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /** One of {@code Ticket.PRIORITIES} (case-insensitive) or null. */
    public static String parsePriority(String raw) {
        if (raw == null) {
            return null;
        }
        String priority = raw.trim().toUpperCase(Locale.ROOT);
        if (!Ticket.PRIORITIES.contains(priority)) {
            throw new GateException(GateErrorCode.USAGE,
                    "priority must be one of " + Ticket.PRIORITIES + " or null");
        }
        return priority;
    }

    /** Trimmed, de-duplicated, size-capped label list; blank entries are dropped. */
    public static List<String> parseLabels(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (String item : raw) {
            if (item == null) {
                throw new GateException(GateErrorCode.USAGE, "tag must not be null");
            }
            String label = item.trim();
            if (label.isEmpty() || labels.contains(label)) {
                continue;
            }
            if (label.length() > Ticket.MAX_LABEL_LENGTH) {
                throw new GateException(GateErrorCode.USAGE,
                        "label longer than " + Ticket.MAX_LABEL_LENGTH + " chars");
            }
            labels.add(label);
        }
        if (labels.size() > Ticket.MAX_LABELS) {
            throw new GateException(GateErrorCode.USAGE, "at most " + Ticket.MAX_LABELS + " labels");
        }
        return labels;
    }

    /** Any {@link TicketStage} name (case-insensitive), blank rejected. */
    public static TicketStage parseStage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "stage must not be blank");
        }
        try {
            return TicketStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GateException(GateErrorCode.USAGE, "no such stage: " + raw);
        }
    }
}
