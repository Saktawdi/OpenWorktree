package gate.domain.ticket;

/**
 * Ticket lifecycle stages (架构落地执行文档 §8.1).
 *
 * <p>The agent can only trigger T2 ({@link #IN_PROGRESS} → {@link #PRESUBMITTED}). Every other
 * transition is driven by the gate itself; in particular PASS/REJECT is minted by
 * {@code GatePolicy}, never by the agent.
 */
public enum TicketStage {
    /** 待处理 */
    PENDING,
    /** 进行中 */
    IN_PROGRESS,
    /** 预提审 */
    PRESUBMITTED,
    /** 审核中 */
    IN_REVIEW,
    /** 已驳回 */
    REJECTED,
    /** 待提交 */
    READY_TO_PUBLISH,
    /** 需人工 */
    NEEDS_HUMAN,
    /** 已完成（终态） */
    DONE,
    /** 已取消（终态） */
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}
