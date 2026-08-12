package gate.domain.git;

import java.util.Locale;

/**
 * A git object name (40-hex SHA-1).
 *
 * <p>Wrapping it is not ceremony: the gate's whole correctness claim is an equality between
 * hashes ({@code snapshot tree == published commit's tree == tree recomputed by pre-receive}),
 * and the pre-receive contract in 架构落地执行文档 §6.3 compares these values as exact strings.
 * A raw {@code String} would let a truncated, upper-cased or whitespace-padded value flow into
 * an approval record and silently fail a {@code grep -qx} comparison inside the hook, which
 * presents as "gate rejects everything" — the silent-rejection failure mode called out in
 * spike-结论 §2.4.
 *
 * <p>The all-zero id is git's "no such ref" sentinel on the pre-receive stdin line; it is
 * representable here on purpose so hook-adjacent code can compare against {@link #ZERO}.
 */
public final class ObjectId {

    /** git's null object name, used by receive-pack for ref creation (old) and deletion (new). */
    public static final ObjectId ZERO = new ObjectId("0000000000000000000000000000000000000000");

    private final String hex;

    private ObjectId(String hex) {
        this.hex = hex;
    }

    /**
     * @throws IllegalArgumentException if the input is not exactly 40 lower-case hex chars after trimming
     */
    public static ObjectId of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("object id must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.length() != 40) {
            throw new IllegalArgumentException("object id must be 40 hex chars, got " + trimmed.length() + ": " + trimmed);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit) {
                throw new IllegalArgumentException("object id must be hex, got: " + trimmed);
            }
        }
        return new ObjectId(lower);
    }

    public String hex() {
        return hex;
    }

    public boolean isZero() {
        return ZERO.hex.equals(hex);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ObjectId other && hex.equals(other.hex);
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
