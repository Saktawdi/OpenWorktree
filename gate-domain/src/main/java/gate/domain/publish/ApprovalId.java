package gate.domain.publish;

import java.util.Locale;

/**
 * Identifier of a one-shot approval record (架构落地执行文档 §6.2).
 *
 * <p>128-bit random, lower-case hex. This is the <em>only</em> value that travels on the wire as
 * {@code --push-option=gate-approval=<id>}, and it is deliberately non-secret: argv is world
 * readable, so authorisation cannot rest on secrecy. It rests on "the record exists, is bound to
 * exactly this {@code (ref, old, new, tree)}, and is consumed exactly once".
 *
 * <p>The charset is constrained here <em>and</em> re-validated inside the hook (judgement ②),
 * because this id is concatenated into a filesystem path: {@code ../../etc/passwd} style ids are
 * the B19 attack.
 */
public final class ApprovalId {

    private final String hex;

    private ApprovalId(String hex) {
        this.hex = hex;
    }

    public static ApprovalId of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("approval id must not be empty");
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.length() != 32) {
            throw new IllegalArgumentException("approval id must be 32 hex chars (128 bit), got " + lower.length());
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                throw new IllegalArgumentException("approval id must be hex");
            }
        }
        return new ApprovalId(lower);
    }

    public String value() {
        return hex;
    }

    /** The exact push-option payload the hook expects. */
    public String pushOption() {
        return "gate-approval=" + hex;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApprovalId other && hex.equals(other.hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public String toString() {
        return hex;
    }
}
