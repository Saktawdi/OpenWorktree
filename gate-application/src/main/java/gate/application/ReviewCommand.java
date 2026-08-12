package gate.application;

/**
 * Input for {@code review}.
 *
 * <p>Two review modes coexist behind the same {@link gate.ports.ReviewEngine} contract:
 * <ul>
 *   <li><b>manual</b> (P1, and still used by A6 to exercise the GatePolicy blocker branch): a human
 *       verdict flows in as a BLOCKER finding, not an {@code EngineFailure};</li>
 *   <li><b>prism</b> (P2): when an engine is configured, {@code GateServiceImpl} selects the live
 *       engine and {@code humanPass}/{@code note} are unused — the engine produces the evidence.</li>
 * </ul>
 * The verdict itself is always derived by {@code GatePolicy}.
 *
 * @param round     round to review; {@code null} means "the latest presubmit round"
 * @param humanPass manual verdict (manual mode only)
 * @param note      recorded as the finding message when {@code humanPass} is false (manual mode only)
 */
public record ReviewCommand(String ticketNo, Integer round, boolean humanPass, String note) {

    public ReviewCommand {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
    }

    /** Convenience for prism mode: no human verdict is carried. */
    public static ReviewCommand forEngine(String ticketNo, Integer round) {
        return new ReviewCommand(ticketNo, round, false, null);
    }
}
