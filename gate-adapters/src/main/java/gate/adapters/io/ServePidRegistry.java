package gate.adapters.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists the PIDs of spawned {@code opencode serve} children so the next adapter construction
 * can reap orphans. Rationale: on Windows, closing the backend console window can terminate the
 * JVM without running shutdown hooks, leaving serve processes alive while PortAllocator state is
 * lost — new sessions then race onto stale servers with outdated in-memory config. Sweeping known
 * PIDs at startup removes them regardless of how the previous run died.
 *
 * <p>Sweeping only destroys processes whose executable path still contains {@code opencode}, so a
 * recycled PID belonging to an unrelated program is never touched.
 */
public final class ServePidRegistry {

    private final Path file;

    public ServePidRegistry(Path file) {
        this.file = file;
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException ignored) {
            }
        }
    }

    public boolean enabled() {
        return file != null;
    }

    public synchronized void record(long pid) {
        if (file == null || pid <= 0) {
            return;
        }
        try {
            Files.writeString(file, pid + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Destroys every recorded PID that still exists and looks like an opencode binary, then clears the registry. */
    public synchronized int sweepOrphans() {
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        Set<Long> pids = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    pids.add(Long.parseLong(line.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
            return 0;
        }
        int killed = 0;
        for (long pid : pids) {
            try {
                ProcessHandle.of(pid).ifPresent(handle -> {
                    String command = handle.info().command().orElse("");
                    if (command.contains("opencode")) {
                        handle.descendants().forEach(ProcessHandle::destroyForcibly);
                        handle.destroyForcibly();
                    }
                });
            } catch (Exception ignored) {
                // already gone / inaccessible
            }
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
        return killed;
    }
}
