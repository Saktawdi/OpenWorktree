package gate.application.publish;

/**
 * Input for {@code publish}.
 *
 * @param round {@code null} means "the latest reviewed round"
 */
public record PublishCommand(String ticketNo, Integer round) {

    public PublishCommand {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
    }
}
