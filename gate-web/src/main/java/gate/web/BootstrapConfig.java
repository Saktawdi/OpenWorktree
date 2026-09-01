package gate.web;

import gate.adapters.config.TomlGateConfigLoader;
import gate.domain.error.GateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First-run config bootstrap: starting the backend without {@code --config} resolves to
 * {@code local-run/gate.toml} and generates it on first run — absolute {@code gate_home}
 * anchored at the config's own directory, loopback web on 18080 — so a fresh clone starts
 * with zero hand-written configuration. Everything else (db, token, auth repo, clones) the
 * backend already creates by itself.
 *
 * <p>An explicit {@code --config} pointing at a missing file stays an error (fail-closed:
 * a typo must not silently materialize as a brand-new config).
 */
final class BootstrapConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapConfig.class);

    private BootstrapConfig() {
    }

    /** Effective config path: the explicit one, or the default — generated when absent. */
    static Path resolve(Path explicit) throws IOException {
        if (explicit != null) {
            return explicit;
        }
        return resolveIn(Path.of("").toAbsolutePath().normalize());
    }

    /** Same decision anchored at an explicit base directory (the process working directory). */
    static Path resolveIn(Path baseDir) throws IOException {
        Path cfg = baseDir.resolve("local-run").resolve("gate.toml");
        if (Files.notExists(cfg)) {
            createDefault(cfg);
        }
        return cfg;
    }

    private static void createDefault(Path cfg) throws IOException {
        // Absolute and slash-separated: the MCP child runs with a ticket clone as cwd, so the
        // generated file must never rely on the launching process's working directory.
        Path gateHome = cfg.getParent().resolve("gate-home");
        String home = gateHome.toAbsolutePath().normalize().toString().replace('\\', '/');
        String content = """
                # OpenWorktree 启动时自动生成的默认配置；可在「设置中心」或直接编辑本文件调整。
                schema_version = 2
                project = "openworktree"
                gate_home = "%s"

                [web]
                bind = "127.0.0.1"
                port = 18080
                """.formatted(home);
        Files.createDirectories(cfg.getParent());
        Path tmp = cfg.resolveSibling(cfg.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            new TomlGateConfigLoader().load(tmp);
            Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING);
        } catch (GateException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // fall through to the rethrow below
            }
            throw new IllegalStateException("generated default config failed validation", e);
        }
        LOG.info("no config found - generated default {}", cfg);
    }
}
