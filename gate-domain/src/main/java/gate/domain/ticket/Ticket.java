package gate.domain.ticket;

import java.time.Instant;

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
        Instant updatedAt) {

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
    }

    public Ticket withStage(TicketStage newStage, Instant now) {
        return new Ticket(ticketNo, title, targetRef, clonePath, executorProviderId, executorModel,
                reviewerProviderId, reviewerModel, newStage, createdAt, now);
    }
}
