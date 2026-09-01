package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * First-run config bootstrap ({@link BootstrapConfig}): starting without {@code --config}
 * materializes {@code local-run/gate.toml} with an absolute {@code gate_home} and loopback web;
 * an existing file is never touched, and an explicit missing path stays an error.
 */
final class BootstrapConfigTest {

    @TempDir
    Path tmp;

    @Test
    void generatesDefaultWhenAbsent() throws IOException {
        Path resolved = BootstrapConfig.resolveIn(tmp);
        assertTrue(Files.exists(resolved), "default config should be generated");
        assertEquals(tmp.resolve("local-run").resolve("gate.toml"), resolved);

        String text = Files.readString(resolved, StandardCharsets.UTF_8);
        // gate_home must be absolute with forward slashes: the MCP child runs with a ticket
        // clone as cwd, so a relative path would resolve somewhere else entirely.
        assertTrue(text.contains("gate_home = \"" + tmp.toString().replace('\\', '/')
                + "/local-run/gate-home\""), "absolute gate_home expected, got: " + text);
        assertTrue(text.contains("port = 18080"));
        assertTrue(text.contains("bind = \"127.0.0.1\""));
    }

    @Test
    void doesNotOverwriteExistingConfig() throws IOException {
        Path localRun = tmp.resolve("local-run");
        Files.createDirectories(localRun);
        Path existing = localRun.resolve("gate.toml");
        Files.writeString(existing, "sentinel", StandardCharsets.UTF_8);

        assertEquals("sentinel", Files.readString(BootstrapConfig.resolveIn(tmp), StandardCharsets.UTF_8),
                "an existing config must never be overwritten");
    }

    @Test
    void explicitPathIsPassedThroughWithoutGeneration() throws IOException {
        Path explicit = tmp.resolve("custom.toml");
        assertEquals(explicit, BootstrapConfig.resolve(explicit));
        assertFalse(Files.exists(explicit), "explicit paths are never auto-generated");
    }

    @Test
    void generatedConfigPassesLoaderValidation() throws IOException {
        Path resolved = BootstrapConfig.resolveIn(tmp);
        // The generated file must be loadable by the strict loader it will be read with.
        var config = new gate.adapters.config.TomlGateConfigLoader().load(resolved);
        assertTrue(config.webConfigured());
        assertEquals(18080, config.web().port());
        assertEquals("127.0.0.1", config.web().bind());
    }

    @Test
    void unwritableLocationFailsLoudly() throws IOException {
        // A file where the config directory should be makes createDirectories fail.
        Path blocker = tmp.resolve("local-run");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> BootstrapConfig.resolveIn(tmp));
    }
}
