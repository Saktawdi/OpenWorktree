package gate.application;

/**
 * Input for {@code review}.
 *
 * <p>In P1 the verdict is supplied by a human, but it still flows through a {@link
 * gate.ports.ReviewEngine} implementation that emits an {@code EngineReport}; the verdict itself is
 * still derived by {@code GatePolicy}. A human "reject" arrives as a BLOCKER finding, not as an
 * {@code EngineFailure} — failures are reserved for the engine malfunctioning.
 *
 * @param round     round to review; {@code null} means "the latest presubmit round"
 * @param humanPass P1 manual verdict
 * @param note      recorded as the finding message when {@code humanPass} is false
 */
public record ReviewCommand(String ticketNo, Integer round, boolean humanPass, String note) {

    public ReviewCommand {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        if (!humanPass && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("a manual reject must carry a note (it becomes the finding message)");
        }
    }
}
