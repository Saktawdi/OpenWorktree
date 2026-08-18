package gate.domain.project;

import java.time.Instant;
import java.util.List;

/**
 * A registered project (web console, codex-style workspace adoption).
 *
 * <p>A project owns one isolated ticket board and its tickets. The gate topology (auth repo, clone
 * root, target refs) is still owned by {@code gate.toml} — one gate instance serves one configured
 * topology (ADR-14).
 *
 * <p>V6 project meta: {@code priority} (P0..P3) and {@code size} are nullable console-managed
 * fields; {@code tags} is a free-form label list. All three are optional — the home board falls
 * back to ticket-derived aggregates when they are unset.
 */
public record Project(
        String id,
        String name,
        String workspacePath,
        String targetRef,
        String authRepo,
        String priority,
        String size,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public static final List<String> PRIORITIES = List.of("P0", "P1", "P2", "P3");
    public static final List<String> SIZES = List.of("small", "medium", "large");
    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_LENGTH = 32;

    public Project {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalArgumentException("workspacePath must not be blank");
        }
        if (priority != null && !PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("priority must be one of " + PRIORITIES + " or null");
        }
        if (size != null && !SIZES.contains(size)) {
            throw new IllegalArgumentException("size must be one of " + SIZES + " or null");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (tags.size() > MAX_TAGS) {
            throw new IllegalArgumentException("at most " + MAX_TAGS + " tags");
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("tag must not be blank");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("tag longer than " + MAX_TAG_LENGTH + " chars: " + tag);
            }
        }
    }
}
