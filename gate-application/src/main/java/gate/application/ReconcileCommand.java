package gate.application;

/** Input for {@code reconcile}. {@code ticketNo} null means "every pending intent". */
public record ReconcileCommand(String ticketNo) {
}
