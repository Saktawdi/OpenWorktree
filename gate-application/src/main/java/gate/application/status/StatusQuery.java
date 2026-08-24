package gate.application.status;

/** Input for {@code status}. {@code ticketNo} null means "every ticket". */
public record StatusQuery(String ticketNo) {
}
