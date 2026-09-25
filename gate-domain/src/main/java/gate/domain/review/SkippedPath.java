package gate.domain.review;

/**
 * A changed path the engine deliberately did NOT review, with the reason why.
 *
 * <p>Engines used to have exactly two states per changed path — covered or a policy coverage gap.
 * Real review pipelines need a third: paths that are <em>structurally not reviewable or explicitly
 * excluded</em> (binary blobs, credential files, generated code whitelisted by a project rule, or a
 * per-file diff exceeding the token gate). Recording them as values keeps the coverage invariant
 * meaningful: the policy can subtract exactly these paths from the coverage denominator instead of
 * discovering them as an unexplained gap, and the evidence chain shows what was left unreviewed
 * and why — nothing is silently dropped.
 *
 * <p>Reasons are a closed set so consumers (policy, console, audit) can switch on them reliably:
 * <ul>
 *   <li>{@code deleted} — the path was deleted by the change; there is no new content to review.</li>
 *   <li>{@code binary} — a binary blob (git marks the section); no textual review applies.</li>
 *   <li>{@code secret_path} — matches the built-in credential-file patterns; secrets must not be
 *       copied into prompts anyway, and a review verdict on them is meaningless.</li>
 *   <li>{@code rule_skip} — a project rule ({@code .gate/rules.json} {@code skip:true}) explicitly
 *       excludes it. This is the project authorising the exclusion, the same authority that owns
 *       the gate configuration.</li>
 *   <li>{@code too_large} — the per-file diff exceeds the token gate. Unlike the reasons above,
 *       real code goes unreviewed here, so the policy routes it to REQUIRES_HUMAN instead of
 *       silently accepting the truncation.</li>
 * </ul>
 */
public record SkippedPath(String path, String reason) {

    public static final String DELETED = "deleted";
    public static final String BINARY = "binary";
    public static final String SECRET_PATH = "secret_path";
    public static final String RULE_SKIP = "rule_skip";
    public static final String TOO_LARGE = "too_large";

    public SkippedPath {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    /** Reasons the policy may subtract from the coverage denominator without human blessing. */
    public static boolean allowedWithoutHuman(String reason) {
        return !TOO_LARGE.equals(reason);
    }
}
