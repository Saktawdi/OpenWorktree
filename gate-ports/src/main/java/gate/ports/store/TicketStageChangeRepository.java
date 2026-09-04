package gate.ports.store;

import gate.domain.ticket.TicketStage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code ticket_stage_change} table (V19 状态变更记录).
 *
 * <p>One row per operator-driven, reason-carrying stage change: 重启（终态 → IN_PROGRESS，
 * T-117）、强制已完成（任意非终态 → DONE）、取消工单（任意非终态 → CANCELLED）。重启是
 * 状态变更的特例，理由口径完全一致（PATCH 拒绝缺理由的流转），因此共用一张历史表。
 *
 * <p>{@code round} 是变更发生时 presubmit 将分配的轮次（{@link PresubmitRepository#nextRound}），
 * 轮次编号跨变更连续。存量 V17 重启行 {@code toStage} 为 NULL，语义等同 IN_PROGRESS。
 */
public interface TicketStageChangeRepository {

    record StageChangeRow(String ticketNo, int round, TicketStage fromStage, TicketStage toStage,
                          String reason, Instant createdAt) {

        /** Legacy V17 restart rows carry no to_stage; their target is always IN_PROGRESS. */
        public TicketStage effectiveToStage() {
            return toStage == null ? TicketStage.IN_PROGRESS : toStage;
        }

        /** True for revive rows (terminal → IN_PROGRESS), incl. legacy NULL to_stage. */
        public boolean isRevive() {
            return effectiveToStage() == TicketStage.IN_PROGRESS;
        }
    }

    void insert(StageChangeRow row);

    /** Full history for one ticket, oldest first. */
    List<StageChangeRow> findByTicket(String ticketNo);

    /** The most recent revive (terminal → IN_PROGRESS), if any — feeds the 系统注入词 重启理由 field. */
    default Optional<StageChangeRow> latestRevive(String ticketNo) {
        List<StageChangeRow> rows = findByTicket(ticketNo);
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).isRevive()) {
                return Optional.of(rows.get(i));
            }
        }
        return Optional.empty();
    }

    default long count(String ticketNo) {
        return findByTicket(ticketNo).size();
    }
}
