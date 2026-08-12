package gate.domain.git;

import java.nio.file.Path;

/**
 * A filesystem location holding a git repository — either the authoritative bare repo
 * ({@code auth.git}) or an agent clone.
 *
 * <p>Kept as a distinct type so port signatures cannot accidentally swap the two: passing an
 * agent clone where {@code auth.git} is expected is exactly the B14 class of mistake
 * (架构落地执行文档 §2.2), and passing {@code auth.git} where a clone is expected would run the
 * snapshot capture against the authoritative repo.
 */
public record RepoRef(Path path) {

    public RepoRef {
        if (path == null) {
            throw new IllegalArgumentException("repo path must not be null");
        }
    }

    public static RepoRef of(Path path) {
        return new RepoRef(path.toAbsolutePath().normalize());
    }

    public String pathString() {
        return path.toString();
    }
}
