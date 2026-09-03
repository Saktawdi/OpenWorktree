package gate.adapters.store;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite wiring (架构落地执行文档 §7.1).
 *
 * <p>PRAGMAs are set on the {@link SQLiteConfig} so they apply to <em>every</em> connection, not
 * just the first one — a per-connection setting silently missed on a pooled connection would drop
 * {@code synchronous=FULL} and with it the durability that invariant I1 depends on.
 *
 * <p>{@code validateOnMigrate} is on: a schema that does not match the migrations refuses to start
 * (exit 22) rather than operating on an unknown shape.
 */
public final class SqliteDataSourceFactory {

    private SqliteDataSourceFactory() {
    }

    public static DataSource create(Path dbPath) {
        Path absolute = dbPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create db directory for " + absolute, e);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);

        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + absolute.toString().replace('\\', '/'));
        return ds;
    }

    /** Runs Flyway and verifies the four PRAGMAs actually took effect. */
    public static void migrate(DataSource dataSource) {
        // Native image: Flyway's classpath scanner cannot enumerate embedded resources
        // ("unsupported protocol: resource"), so NativeMigrations materializes the SQL files
        // into a temp dir and we point Flyway at it. JVM deployments: classpath as before.
        String location;
        try {
            location = NativeMigrations.extractTo(
                    SqliteDataSourceFactory.class.getClassLoader(),
                    Path.of(System.getProperty("java.io.tmpdir"), "gate-migrations"));
        } catch (java.io.IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot materialize native migrations", e);
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location != null ? location : "classpath:db/migration")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        assertPragmas(dataSource);
    }

    private static void assertPragmas(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String journal = scalar(st, "PRAGMA journal_mode");
            if (!"wal".equalsIgnoreCase(journal)) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "expected journal_mode=WAL, got " + journal);
            }
            String sync = scalar(st, "PRAGMA synchronous");
            // SQLite reports FULL as 2. Anything lower means publish_intent may not be durable
            // before commit-tree runs, which breaks invariant I1.
            if (!"2".equals(sync)) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "expected synchronous=FULL (2), got " + sync);
            }
            String fk = scalar(st, "PRAGMA foreign_keys");
            if (!"1".equals(fk)) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "expected foreign_keys=ON, got " + fk);
            }
        } catch (SQLException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot verify SQLite pragmas", e);
        }
    }

    private static String scalar(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
