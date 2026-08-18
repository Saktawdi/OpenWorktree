package gate.adapters.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CliLocator}'s pure resolution logic. Everything here runs on any platform —
 * Windows-specific behaviour is exercised through the {@code where} output parser and candidate
 * builders rather than by actually spawning {@code where.exe}.
 */
class CliLocatorTest {

    @TempDir
    Path tempDir;

    @Test
    void whereCandidatesFiltersAliasesAndBlankLines() {
        List<Path> candidates = CliLocator.whereCandidates(List.of(
                "",
                "   ",
                "C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\claude.exe",
                "C:\\Program Files\\Git\\cmd\\git.exe"));
        assertEquals(List.of(Path.of("C:\\Program Files\\Git\\cmd\\git.exe")), candidates);
    }

    @Test
    void whereCandidatesMapsExtensionlessShimToRunnableSibling() throws IOException {
        Path npmDir = Files.createDirectories(tempDir.resolve("npm"));
        Files.createFile(npmDir.resolve("claude.cmd"));
        List<Path> candidates = CliLocator.whereCandidates(List.of(
                npmDir.resolve("claude").toString(),
                npmDir.resolve("claude.cmd").toString()));
        // The shim line and the .cmd line collapse into one deduped candidate.
        assertEquals(List.of(npmDir.resolve("claude.cmd")), candidates);
    }

    @Test
    void whereCandidatesDropsExtensionlessShimWithoutSibling() {
        List<Path> candidates = CliLocator.whereCandidates(List.of(
                "C:\\tools\\bin\\opencode"));
        assertEquals(List.of(), candidates);
    }

    @Test
    void candidatesInOrdersCmdBeforeExeOnWindows() {
        Path dir = Path.of("C:\\npm");
        assertEquals(List.of(dir.resolve("claude.cmd"), dir.resolve("claude.exe")),
                CliLocator.candidatesIn(dir, "claude", true));
        assertEquals(List.of(dir.resolve("claude")), CliLocator.candidatesIn(dir, "claude", false));
    }

    @Test
    void windowsSearchDirsFollowEnvironment() {
        List<Path> dirs = CliLocator.windowsSearchDirs(Map.of(
                "APPDATA", "C:\\Users\\u\\AppData\\Roaming",
                "LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local"));
        assertEquals(List.of(
                Path.of("C:\\Users\\u\\AppData\\Roaming", "npm"),
                Path.of("C:\\Users\\u\\AppData\\Local", "Volta", "bin")), dirs);
    }

    @Test
    void windowsSearchDirsSkipMissingEnvironment() {
        assertEquals(List.of(), CliLocator.windowsSearchDirs(Map.of("APPDATA", "  ")));
    }

    @Test
    void unixSearchDirsIncludeHomeAndSystemBins() {
        List<Path> dirs = CliLocator.unixSearchDirs(Map.of("HOME", "/home/u"));
        assertEquals(List.of(
                Path.of("/home/u", ".local", "bin"),
                Path.of("/home/u", ".volta", "bin"),
                Path.of("/home/u", ".npm-global", "bin"),
                Path.of("/opt", "homebrew", "bin"),
                Path.of("/usr", "local", "bin")), dirs);
    }

    @Test
    void locatePassesExplicitPathThrough() throws IOException {
        CliLocator locator = new CliLocator(new ProcessRunnerImpl(tempDir.resolve("proc")));
        Path file = Files.createFile(tempDir.resolve("engine.cmd"));

        assertEquals(Optional.of(file.toAbsolutePath().normalize()), locator.locate(file.toString()));
        assertEquals(Optional.empty(), locator.locate(tempDir.resolve("missing.cmd").toString()));
        assertEquals(Optional.empty(), locator.locate("  "));
    }

    /** The test environment always has git on PATH (other tests shell out to it) — resolution must find it. */
    @Test
    void locateFindsGitOnPath() throws IOException {
        CliLocator locator = new CliLocator(new ProcessRunnerImpl(tempDir.resolve("proc")));
        Optional<Path> git = locator.locate("git");
        assertTrue(git.isPresent(), "git must be resolvable on the test machine");
        assertTrue(Files.isRegularFile(git.get()));
    }
}
