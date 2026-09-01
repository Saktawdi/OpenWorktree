package gate.domain.project;

import java.time.Instant;
import java.util.List;

/**
 * A registered project (web console, codex-style workspace adoption).
 *
 * <p>A project owns one isolated ticket board and its tickets. Each project carries its own
 * {@code authRepo} (provisioned at registration) so its tickets clone from — and publish back to —
 * that repo; {@code gate.toml} remains the fallback topology for unaffiliated tickets (ADR-14,
 * amended after a shared auth repo leaked one project's history into every other project's
 * clones).
 *
 * <p>V6 project meta: {@code priority} (P0..P3) and {@code size} are nullable console-managed
 * fields; {@code tags} is a free-form label list. All three are optional — the home board falls
 * back to ticket-derived aggregates when they are unset.
 *
 * <p>Console ordering meta: {@code starred} pins a project to the top of the home board;
 * {@code sortOrder} is the manual drag order (0 = unset, falls back to name ordering; the reorder
 * endpoint assigns 1..N in display order).
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
        boolean starred,
        long sortOrder,
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
