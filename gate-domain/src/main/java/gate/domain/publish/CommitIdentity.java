package gate.domain.publish;

/**
 * Fixed commit identity + date (架构落地执行文档 §7.4 I1/I5).
 *
 * <p>Determinism is load-bearing, not cosmetic: crash recovery at C2 re-runs {@code commit-tree}
 * and relies on "same input ⇒ same SHA". That only holds if author/committer/date are pinned via
 * environment variables and never inherited from the user's git config.
 *
 * @param date git raw date form, e.g. {@code "1700000000 +0000"}
 */
public record CommitIdentity(String name, String email, String date) {

    public CommitIdentity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("date must not be blank");
        }
    }
}
