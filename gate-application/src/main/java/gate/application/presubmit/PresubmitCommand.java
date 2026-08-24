package gate.application.presubmit;

/** Input for {@code presubmit} (T2 — the only transition an agent may trigger). */
public record PresubmitCommand(String ticketNo) {

    public PresubmitCommand {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
    }
}
