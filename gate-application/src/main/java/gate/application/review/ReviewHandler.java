package gate.application.review;

import gate.application.ReviewCommand;
import gate.application.ReviewResult;

/**
 * Review capability handler (EX-002).
 * Owns GatePolicy verdict, engine orchestration, review_result core fields.
 * Metrics must not be written here (ownership-catalog: metrics is derived projection).
 */
public final class ReviewHandler {
    public ReviewResult handle(ReviewCommand cmd) {
        throw new UnsupportedOperationException("ReviewHandler not yet wired - see EX-002");
    }
}
