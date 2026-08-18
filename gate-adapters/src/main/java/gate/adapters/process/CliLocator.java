package gate.adapters.process;

import gate.ports.ProcessRunner;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves a bare CLI name ({@code claude}, {@code opencode}, …) to the executable file a terminal
 * would actually run, so callers can execute the <em>resolved absolute path</em> instead of the
 * bare name.
 *
 * <p>Why resolution is needed: on Windows, npm-installed CLIs are {@code .cmd} shims under
 * {@code %APPDATA%\npm}. {@code ProcessBuilder} backs onto {@code CreateProcess}, which only
 * appends {@code .exe} when resolving a bare name — a {@code claude.cmd} is invisible to it and
 * the probe fails with CreateProcess error=2 even though the CLI is installed. Same approach as
 * cc-switch: locate the real file first, then execute it. Handing the JDK a path ending in
 * {@code .cmd} is safe: {@code ProcessImpl} wraps batch scripts in {@code cmd.exe /c} itself.
 *
 * <p>Resolution order on Windows: {@code %SystemRoot%\System32\where.exe $PATH:<name>} (the
 * {@code $PATH:} form searches PATH only, never the working directory), skipping App Execution
 * Aliases under {@code Microsoft\WindowsApps} (they launch the Store, not the CLI) and mapping
 * extensionless npm shims to their runnable {@code .cmd}/{@code .exe} sibling. If PATH has no
 * hit, fall back to scanning the common install dirs (npm global, volta, nvm). On other platforms
 * the PATH directories are scanned directly, again with a small fallback list.
 *
 * <p>Locating never throws — an unresolvable name yields {@link Optional#empty()} and the caller
 * falls back to the bare name, which keeps behaviour identical to before this class existed.
 */
public final class CliLocator {

    private static final Duration LOCATE_TIMEOUT = Duration.ofSeconds(5);

    private final ProcessRunner processRunner;

    public CliLocator(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /** Resolves {@code executable} to a runnable file, or empty when it cannot be located. */
    public Optional<Path> locate(String executable) {
        if (executable == null || executable.isBlank()) {
            return Optional.empty();
        }
        String name = executable.trim();
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            // An explicit path is the user's decision — pass it through when it exists.
            return regularFile(Path.of(name));
        }
        return isWindows() ? locateOnWindows(name) : locateOnUnix(name);
    }

    private Optional<Path> locateOnWindows(String name) {
        for (Path candidate : whereCandidates(runWhere(name))) {
            Optional<Path> file = regularFile(candidate);
            if (file.isPresent()) {
                return file;
            }
        }
        return scanDirectories(windowsSearchDirs(System.getenv()), name, true);
    }

    private Optional<Path> locateOnUnix(String name) {
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    dirs.add(Path.of(dir));
                }
            }
        }
        dirs.addAll(unixSearchDirs(System.getenv()));
        return scanDirectories(dirs, name, false);
    }

    /** Runs {@code where.exe $PATH:<name>}; any failure yields no lines (caller falls back). */
    private List<String> runWhere(String name) {
        String systemRoot = System.getenv("SystemRoot");
        Path where = Path.of(systemRoot == null ? "C:\\Windows" : systemRoot, "System32", "where.exe");
        if (!Files.isRegularFile(where)) {
            return List.of();
        }
        try {
            ProcessRunner.ProcRun run = processRunner.run(
                    List.of(where.toString(), "$PATH:" + name), null, Map.of(), LOCATE_TIMEOUT);
            // exit 1 = "could not find file" — not an error worth distinguishing here.
            return run.ok() || run.exitCode() == 1 ? run.stdout().lines().toList() : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * Parses {@code where.exe} output into ordered, distinct candidate files: blank lines dropped,
     * App Execution Aliases skipped, extensionless npm shims replaced by their existing runnable
     * sibling (dropped when no sibling exists — {@code CreateProcess} cannot run a sh script).
     */
    static List<Path> whereCandidates(List<String> whereLines) {
        Map<String, Path> seen = new LinkedHashMap<>();
        for (String raw : whereLines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Path path = Path.of(line);
            if (isAppExecutionAliasDir(path.getParent())) {
                continue;
            }
            Path candidate = hasExtension(path) ? path : runnableSibling(path);
            if (candidate != null) {
                seen.putIfAbsent(candidate.normalize().toString().toLowerCase(Locale.ROOT), candidate);
            }
        }
        return List.copyOf(seen.values());
    }

    /** Candidate file names for {@code name} inside {@code dir}: {@code .cmd} before {@code .exe} on Windows. */
    static List<Path> candidatesIn(Path dir, String name, boolean windows) {
        if (windows) {
            return List.of(dir.resolve(name + ".cmd"), dir.resolve(name + ".exe"));
        }
        return List.of(dir.resolve(name));
    }

    /**
     * Common install dirs probed when PATH yielded nothing. Derived from the environment so it
     * tracks npm/volta/nvm wherever the user put them.
     */
    static List<Path> windowsSearchDirs(Map<String, String> env) {
        List<Path> dirs = new ArrayList<>();
        addDir(dirs, env.get("APPDATA"), "npm");
        addDir(dirs, env.get("LOCALAPPDATA"), "Volta", "bin");
        addDir(dirs, env.get("VOLTA_HOME"), "bin");
        addDir(dirs, env.get("NVM_HOME"));
        return dirs;
    }

    /** Unix counterparts: user-level bin dirs that a non-login JVM process may not have on PATH. */
    static List<Path> unixSearchDirs(Map<String, String> env) {
        List<Path> dirs = new ArrayList<>();
        addDir(dirs, env.get("HOME"), ".local", "bin");
        addDir(dirs, env.get("HOME"), ".volta", "bin");
        addDir(dirs, env.get("HOME"), ".npm-global", "bin");
        dirs.add(Path.of("/opt/homebrew", "bin"));
        dirs.add(Path.of("/usr", "local", "bin"));
        return dirs;
    }

    /** True when {@code dir} sits under {@code Microsoft\WindowsApps} (App Execution Aliases). */
    static boolean isAppExecutionAliasDir(Path dir) {
        if (dir == null) {
            return false;
        }
        String normalized = dir.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        return normalized.contains("microsoft\\windowsapps");
    }

    private static Optional<Path> scanDirectories(List<Path> dirs, String name, boolean windows) {
        for (Path dir : dirs) {
            for (Path candidate : candidatesIn(dir, name, windows)) {
                Optional<Path> file = regularFile(candidate);
                if (file.isPresent()) {
                    return file;
                }
            }
        }
        return Optional.empty();
    }

    /** An extensionless npm shim cannot be executed — prefer an existing {@code .cmd}/{@code .exe} sibling. */
    private static Path runnableSibling(Path extensionless) {
        return Stream.of(".cmd", ".exe")
                .map(ext -> extensionless.resolveSibling(extensionless.getFileName().toString() + ext))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasExtension(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.lastIndexOf('.') > 0;
    }

    private static Optional<Path> regularFile(Path path) {
        return Files.isRegularFile(path) ? Optional.of(path.toAbsolutePath().normalize()) : Optional.empty();
    }

    private static void addDir(List<Path> dirs, String base, String... rest) {
        if (base == null || base.isBlank()) {
            return;
        }
        dirs.add(Path.of(base, rest));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
