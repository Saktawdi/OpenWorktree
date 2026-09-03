package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.error.GateException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the NativeMigrations contract: JVM runtimes are untouched (null → Flyway keeps scanning the
 * classpath); a native runtime with no manifest refuses loudly (an empty schema used to surface
 * three layers later as "no such table: provider"); and extraction materializes exactly the
 * manifest-listed files into the staging dir, serving them back as a filesystem: location.
 */
class NativeMigrationsTest {

    /** Non-native JVM: always null, regardless of what the classpath carries. */
    @Test
    void jvmRuntimeNeverExtracts(@TempDir Path dir) throws Exception {
        assertNull(NativeMigrations.extractTo(loaderWithManifest(dir), dir));
        // and no staging directory is even created
        assertTrue(!Files.exists(dir.resolve("V1__init.sql")));
    }

    @Test
    void nativeRuntimeWithoutManifestFailsLoudly(@TempDir Path dir) throws Exception {
        // classloader with db/migration present but NO manifest.txt inside
        Files.createDirectories(dir.resolve("db/migration"));
        URL[] urls = { dir.toUri().toURL() };
        ClassLoader bare = new URLClassLoader("bare-fixture", urls, null);
        // simulate the native-image runtime marker for the duration of the call
        System.setProperty(NativeMigrations.NATIVE_MARKER, "runtime");
        try {
            GateException e = assertThrows(GateException.class,
                    () -> NativeMigrations.extractTo(bare, dir));
            assertTrue(e.getMessage().contains("manifest"));
        } finally {
            System.clearProperty(NativeMigrations.NATIVE_MARKER);
        }
    }

    @Test
    void nativeRuntimeExtractsManifestListingsToFilesystem(@TempDir Path dir) throws Exception {
        System.setProperty(NativeMigrations.NATIVE_MARKER, "runtime");
        try {
            Path staging = dir.resolve("staging");
            String location = NativeMigrations.extractTo(loaderWithManifest(dir), staging);
            assertNotNull(location);
            assertTrue(location.startsWith("filesystem:"), location);
            assertTrue(Files.readString(staging.resolve("V1__init.sql")).contains("CREATE TABLE t"));
            assertEquals(1, Files.list(staging).count(), "only manifest-listed files are staged");
        } finally {
            System.clearProperty(NativeMigrations.NATIVE_MARKER);
        }
    }

    /** Classloader over a temp dir shaped like the staged classes: db/migration + manifest. */
    private static ClassLoader loaderWithManifest(Path root) throws Exception {
        Path migrationDir = root.resolve("db/migration");
        Files.createDirectories(migrationDir);
        Files.writeString(migrationDir.resolve("manifest.txt"), "V1__init.sql\n");
        Files.writeString(migrationDir.resolve("V1__init.sql"), "CREATE TABLE t(x);");
        URL[] urls = { root.toUri().toURL() };
        return new URLClassLoader("migration-fixture", urls, null);
    }
}
