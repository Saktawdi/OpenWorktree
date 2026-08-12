package gate.domain.review;

/** Normalised finding severity. Closed set; adapters map engine-specific words onto it. */
public enum Severity {
    BLOCKER,
    WARNING,
    NIT,
    INFO
}
