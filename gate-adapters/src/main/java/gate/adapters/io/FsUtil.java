package gate.adapters.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small filesystem helpers shared by adapters. */
public final class FsUtil {

    private FsUtil() {
    }

    /**
     * Recursively deletes a tree, best effort.
     *
     * <p>On Windows git leaves read-only files under {@code .git/objects}; each file is flagged
     * writable before deletion. Failures are swallowed because callers use this only to clean up
     * throwaway temp/sandbox trees — a leftover there is inert.
     */
    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
