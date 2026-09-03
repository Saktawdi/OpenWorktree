package gate.adapters.store;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bridges Flyway to the GraalVM native image for SQL migrations (schema bootstrapping is the one
 * thing the web binary cannot boot without).
 *
 * <p>Why this exists: Flyway's ClassPathScanner enumerates location entries via {@code
 * ClassLoader.getResources} and then opens each entry by URL protocol. In a native image the
 * embedded resources surface with the {@code resource:} protocol, which the scanner refuses
 * ("unsupported protocol: resource"), so {@code classpath:db/migration} silently yields zero
 * migrations and the first query dies on a missing table. We cannot patch Flyway, but every
 * migration file itself IS readable via {@code getResourceAsStream} (native image serves exact-path
 * resource lookups fine) — so in native mode we copy the files named in a build-generated manifest
 * to a temp directory and point Flyway at a {@code filesystem:} location instead.
 *
 * <p>The manifest ({@code db/migration/manifest.txt}, one filename per line) must be generated at
 * NATIVE BUILD time into the staged classes — a runtime classpath scan is exactly what native image
 * cannot do. JVM deployments never take this path: the property check short-circuits to {@code
 * null} and Flyway scans the classpath as before.
 */
final class NativeMigrations {

    /** Set by Substrate VM at image runtime; absent on a regular JVM. Package-visible for tests. */
    static final String NATIVE_MARKER = "org.graalvm.nativeimage.imagecode";

    static final String MANIFEST_RESOURCE = "db/migration/manifest.txt";

    private NativeMigrations() {
    }

    static boolean runningNative() {
        return System.getProperty(NATIVE_MARKER) != null;
    }

    /**
     * Copies the manifest-listed migrations into {@code targetDir} and returns a Flyway location
     * string for them. Contract:
     * <ul>
     *   <li>non-native runtime → {@code null} (caller keeps {@code classpath:db/migration});</li>
     *   <li>native without a manifest → {@link GateErrorCode#GATE_ERROR_IO} failure — a native
     *       build that forgot the manifest would otherwise boot with an empty schema and crash
     *       three layers later with "no such table", far from the real cause.</li>
     * </ul>
     *
     * @param loader    classloader carrying the embedded resources (test seam)
     * @param targetDir staging directory; created when missing
     */
    static String extractTo(java.lang.ClassLoader loader, Path targetDir) throws java.io.IOException {
        if (!runningNative()) {
            return null;
        }
        List<String> names;
        try (InputStream in = loader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "native build is missing " + MANIFEST_RESOURCE + " — the native packaging "
                                + "step must generate the migration manifest (one filename per line) "
                                + "next to the SQL files; without it Flyway finds no migrations and "
                                + "boot dies on the first query");
            }
            names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .toList();
        }
        if (names.isEmpty()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    MANIFEST_RESOURCE + " is empty — the native packaging step generated no "
                            + "migration list, refusing to boot with an empty schema");
        }
        Files.createDirectories(targetDir);
        for (String name : names) {
            String resource = "db/migration/" + name;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new GateException(GateErrorCode.GATE_ERROR_IO,
                            "manifest lists " + name + " but the resource " + resource
                                    + " is not embedded in the native image");
                }
                Files.copy(in, targetDir.resolve(name),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return "filesystem:" + targetDir.toAbsolutePath().toString();
    }
}
