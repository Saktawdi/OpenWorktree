package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.config.TomlGateConfigLoader;
import gate.domain.config.GateConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分根布局迁移：壳把旧树 gate-home/clones/auth 搬入轻根/重根后写 layout-migrate.json，
 * 后端启动时把 DB 行内旧绝对路径重基（clone_path 绝对→相对新克隆根，project.auth_repo
 * 前缀改写），此后仓库层按新克隆根相对存取/绝对解析。
 */
class LegacyLayoutMigrationTest {

    @Test
    void rebases_legacy_absolute_rows_and_repos_roundtrip_relative(@TempDir Path dir) throws Exception {
        // 布局配置（新布局：轻根 gate-home + 重根 clones/auth）
        Path newHome = dir.resolve("new").resolve("gate-home");
        Path clones = dir.resolve("new").resolve("clones");
        Path auth = dir.resolve("new").resolve("auth");
        Files.createDirectories(newHome);
        Path toml = dir.resolve("gate.toml");
        Files.writeString(toml, "schema_version = 2\nproject = \"p\"\n"
                + "auth_repo = \"" + auth.resolve("auth.git").toString().replace('\\', '/') + "\"\n"
                + "clones_root = \"" + clones.toString().replace('\\', '/') + "\"\n"
                + "gate_home = \"" + newHome.toString().replace('\\', '/') + "\"\n");
        GateConfig config = new TomlGateConfigLoader().load(toml);

        DataSource ds = SqliteDataSourceFactory.create(newHome.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String oldClones = dir.resolve("old").resolve("local-run").resolve("clones").toString().replace('\\', '/');
        String oldAuthParent = dir.resolve("old").resolve("local-run").toString().replace('\\', '/');
        // 存量旧布局行（克隆根内绝对路径 + 用户项目工作区 + 旧 auth 镜像）
        jdbc.update("INSERT INTO ticket(ticket_no, title, target_ref, clone_path, stage, created_at, updated_at, labels, is_super) "
                + "VALUES ('T-1','a','refs/heads/main',?,'IN_PROGRESS','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','[]',0)",
                oldClones + "/T-1");
        jdbc.update("INSERT INTO ticket(ticket_no, title, target_ref, clone_path, stage, created_at, updated_at, labels, is_super) "
                + "VALUES ('T-2','quick','refs/heads/main',?,'IN_PROGRESS','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','[]',1)",
                dir.resolve("user-workspace").toString());
        jdbc.update("INSERT INTO provider(id, name, base_url, api_key_ref, type, created_at, updated_at) "
                + "VALUES ('prov1','p','local://p','none','manual','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO agent_config(id, name, cli, provider_id, model, created_at, updated_at) "
                + "VALUES ('ac1','opencode','OPENCODE','prov1','m','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO agent_session(id, ticket_no, agent_config_id, cli, status, clone_path, started_at) "
                + "VALUES ('s1','T-1','ac1','OPENCODE','ACTIVE',?,'2026-01-01T00:00:00Z')",
                oldClones + "/T-1");
        jdbc.update("INSERT INTO project(id, name, workspace_path, auth_repo, created_at, updated_at) "
                + "VALUES ('p1','Proj',?,'" + oldAuthParent + "/auth-p1.git','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",
                dir.resolve("user-workspace").toString());

        // 标记文件（壳落下）
        Path marker = newHome.resolveSibling("layout-migrate.json");
        Files.writeString(marker, "{\"old_clones_root\":\"" + oldClones
                + "\",\"old_auth_parent\":\"" + oldAuthParent + "\"}", StandardCharsets.UTF_8);

        LegacyLayoutMigration.apply(config, jdbc);

        // clone_path 已相对化；快速模式用户工作区保持绝对
        assertEquals("T-1", jdbc.queryForObject("SELECT clone_path FROM ticket WHERE ticket_no = 'T-1'", String.class));
        assertEquals(dir.resolve("user-workspace").toString(),
                jdbc.queryForObject("SELECT clone_path FROM ticket WHERE ticket_no = 'T-2'", String.class));
        assertEquals("T-1", jdbc.queryForObject("SELECT clone_path FROM agent_session WHERE id = 's1'", String.class));
        // project.auth_repo 前缀改写进新重根
        assertEquals(auth.resolve("auth-p1.git").toString(),
                jdbc.queryForObject("SELECT auth_repo FROM project WHERE id = 'p1'", String.class));
        // 标记消费后改名保留
        assertTrue(Files.notExists(marker));
        assertTrue(Files.exists(newHome.resolveSibling("layout-migrate.done.json")));

        // 仓库层读写按新克隆根相对化往返
        JdbcTicketRepository tickets = new JdbcTicketRepository(jdbc, config.clonesRoot());
        JdbcSessionRepository sessions = new JdbcSessionRepository(jdbc, new gate.adapters.blob.FsBlobStore(newHome.resolve("blobs")), config.clonesRoot());
        var t = tickets.find("T-1");
        assertTrue(t.isPresent());
        assertEquals(clones.resolve("T-1").toString(), t.get().clonePath());
        assertEquals(clones.resolve("T-1").toString(), sessions.find("s1").get().clonePath());
        tickets.insert(new gate.domain.ticket.Ticket("T-3", "b", "refs/heads/main",
                config.clonesRoot().resolve("T-3").toString(), null, null, null, null,
                gate.domain.ticket.TicketStage.IN_PROGRESS, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, null,
                null, null, java.util.List.of(), false));
        assertEquals("T-3", jdbc.queryForObject("SELECT clone_path FROM ticket WHERE ticket_no = 'T-3'", String.class));
    }

    @Test
    void no_marker_is_noop(@TempDir Path dir) throws Exception {
        Path newHome = dir.resolve("gate-home");
        Files.createDirectories(newHome);
        Path toml = dir.resolve("gate.toml");
        Files.writeString(toml, "schema_version = 2\nproject = \"p\"\ngate_home = \""
                + newHome.toString().replace('\\', '/') + "\"\n");
        GateConfig config = new TomlGateConfigLoader().load(toml);
        DataSource ds = SqliteDataSourceFactory.create(newHome.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        LegacyLayoutMigration.apply(config, new JdbcTemplate(ds));
        assertTrue(Files.notExists(newHome.resolveSibling("layout-migrate.done.json")));
    }
}
