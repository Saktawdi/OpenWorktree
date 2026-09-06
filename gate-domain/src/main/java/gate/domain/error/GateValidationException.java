package gate.domain.error;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A request failed parameter-level validation: one or more fields are missing, mistyped or carry
 * values outside the accepted set (T-108).
 *
 * <p>Distinct from a plain {@link GateException}: a validation exception is a <em>fixable request
 * problem</em> (the caller can repair the listed fields and retry), while a plain USAGE/domain
 * error reflects a state-dependent rule (duplicate ticket_no, unknown project, missing auth
 * repo...). Every field problem is carried both in the message (so a client that only shows
 * {@code message} still names the broken field) and in the structured {@link #fieldErrors()}
 * list (for {@code error.data} / {@code detail} envelopes).
 */
public class GateValidationException extends GateException {

    private final List<FieldError> fieldErrors;

    /**
     * @param code        the exit/HTTP family (normally {@code USAGE})
     * @param subject     what failed, e.g. {@code "invalid ticket_create request"}
     * @param fieldErrors one entry per broken field; may be empty only when {@code strictMessage}
     *                    semantics are not needed
     */
    public GateValidationException(GateErrorCode code, String subject, List<FieldError> fieldErrors) {
        super(code, compose(subject, fieldErrors));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** The field problems as JSON-serialisable maps (for {@code error.data} / {@code detail}). */
    public List<Map<String, Object>> fieldData() {
        return fieldErrors.stream().map(FieldError::toMap).toList();
    }

    private static String compose(String subject, List<FieldError> errors) {
        String joined = errors.stream().map(FieldError::render).collect(Collectors.joining("; "));
        return subject + ": " + joined;
    }
}
