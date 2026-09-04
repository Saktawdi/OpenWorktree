package gate.domain.ticket;

import java.time.Instant;
import java.util.List;

/**
 * Minimal ticket entity (执行文档 D4: kept as the aggregation unit for cost telemetry in P4).
 *
 * <p>{@code executor*} / {@code reviewer*} are nullable in P1 — the orchestration layer fills the
 * executor coordinates, and P1's reviewer is the {@code manual} provider.
 */
public record Ticket(
        String ticketNo,
        String title,
        String targetRef,
        String clonePath,
        String executorProviderId,
        String executorModel,
        String reviewerProviderId,
        String reviewerModel,
        TicketStage stage,
        Instant createdAt,
        Instant updatedAt,
        // P4 cost telemetry (nullable — usually NULL because this project does not spawn the agent)
        Long execTokenTotal,
        String execTokenSource,
        // S4: optional AgentConfig bound at creation time (nullable)
        String agentConfigId,
        // V5: queue priority P0..P3 (nullable = unset) and project ownership
        String priority,
        String projectId,
        // V8: editable work item content (nullable text + normalized labels)
        String description,
        String note,
        List<String> labels,
        // V19 快速模式: the project's permanent super ticket (clone_path IS the workspace,
        // never closes, bypasses the gate pipeline). Existing rows default to false.
        boolean isSuper) {

    /** Valid priority levels, ordered most urgent first. */
    public static final List<String> PRIORITIES = List.of("P0", "P1", "P2", "P3");
    public static final int MAX_LABELS = 20;
    public static final int MAX_LABEL_LENGTH = 32;

    /**
     * Backward-compatible constructor for callers that don't have P4 cost data (P1/P2 paths).
     * Passes null for both cost fields and the agent config.
     */
    public Ticket(String ticketNo, String title, String targetRef, String clonePath,
                  String executorProviderId, String executorModel,
                  String reviewerProviderId, String reviewerModel,
                  TicketStage stage, Instant createdAt, Instant updatedAt) {
        this(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, stage, createdAt, updatedAt, null, null, null,
                null, null, null, null, List.of());
    }

    /**
     * Backward-compatible constructor for P4 cost data without an agent config.
     */
    public Ticket(String ticketNo, String title, String targetRef, String clonePath,
                  String executorProviderId, String executorModel,
                  String reviewerProviderId, String reviewerModel,
                  TicketStage stage, Instant createdAt, Instant updatedAt,
                  Long execTokenTotal, String execTokenSource) {
        this(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, stage, createdAt, updatedAt,
                execTokenTotal, execTokenSource, null, null, null, null, null, List.of());
    }

    /**
     * Backward-compatible constructor for S4 (agent config, no V5 metadata).
     */
    public Ticket(String ticketNo, String title, String targetRef, String clonePath,
                  String executorProviderId, String executorModel,
                  String reviewerProviderId, String reviewerModel,
                  TicketStage stage, Instant createdAt, Instant updatedAt,
                  Long execTokenTotal, String execTokenSource, String agentConfigId) {
        this(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, stage, createdAt, updatedAt,
                execTokenTotal, execTokenSource, agentConfigId, null, null, null, null, List.of());
    }

    /**
     * Backward-compatible constructor for V5 metadata without editable work item content.
     */
    public Ticket(String ticketNo, String title, String targetRef, String clonePath,
                  String executorProviderId, String executorModel,
                  String reviewerProviderId, String reviewerModel,
                  TicketStage stage, Instant createdAt, Instant updatedAt,
                  Long execTokenTotal, String execTokenSource, String agentConfigId,
                  String priority, String projectId) {
        this(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, stage, createdAt, updatedAt,
                execTokenTotal, execTokenSource, agentConfigId, null, null, null, null, List.of());
    }

    /**
     * Backward-compatible constructor for the pre-V19 shape (no super-ticket flag): every
     * ticket materialized before V19 is a regular gate-pipeline work item.
     */
    public Ticket(String ticketNo, String title, String targetRef, String clonePath,
                  String executorProviderId, String executorModel,
                  String reviewerProviderId, String reviewerModel,
                  TicketStage stage, Instant createdAt, Instant updatedAt,
                  Long execTokenTotal, String execTokenSource, String agentConfigId,
                  String priority, String projectId,
                  String description, String note, List<String> labels) {
        this(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, stage, createdAt, updatedAt,
                execTokenTotal, execTokenSource, agentConfigId, priority, projectId,
                description, note, labels, false);
    }

    public Ticket {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef must not be blank");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (priority != null && !PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("priority must be one of " + PRIORITIES + " or null");
        }
        labels = labels == null ? List.of() : List.copyOf(labels);
        if (labels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("at most " + MAX_LABELS + " labels");
        }
        for (String label : labels) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
            if (label.length() > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException(
                        "label longer than " + MAX_LABEL_LENGTH + " chars: " + label);
            }
        }
    }

    public Ticket withStage(TicketStage newStage, Instant now) {
        return new Ticket(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, newStage, createdAt, now,
                execTokenTotal, execTokenSource, agentConfigId, priority, projectId,
                description, note, labels, isSuper);
    }
}
