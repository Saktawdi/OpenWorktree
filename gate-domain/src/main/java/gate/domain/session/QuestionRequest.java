package gate.domain.session;

import java.util.List;

/**
 * One pending user-question request surfaced by an opencode serve instance (the
 * {@code question.asked} event, or the {@code /question} snapshot) — the payload of the
 * built-in {@code question} tool while it waits for the user's answer.
 *
 * @param requestId opencode request id ("que_…")
 * @param questions the questions to answer, in order
 * @param messageId originating assistant message id, nullable
 * @param callId    originating tool-call id, nullable
 */
public record QuestionRequest(
        String requestId,
        List<QuestionPrompt> questions,
        String messageId,
        String callId) {

    public QuestionRequest {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    /** One question shown to the user. */
    public record QuestionPrompt(
            String question,
            String header,
            List<QuestionOption> options,
            boolean multiple,
            boolean custom) {

        public QuestionPrompt {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** One selectable choice. */
    public record QuestionOption(String label, String description) {
    }
}
