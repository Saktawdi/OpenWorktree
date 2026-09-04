package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import gate.ports.store.TicketStageChangeRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V19 升级路径：在只有 V1..V18 的存量库上（含 ticket_restart 存量行）执行 V19 迁移后，
 * 重启历史读作 to_stage=NULL（语义等同 IN_PROGRESS 的 revive 行），ticket 表获得 is_super
 * 列且一项目至多一条超级工单（部分唯一索引）。
 */
class V19MigrationTest {

    @Test
    void legacy_restart_rows_read_as_revives_and_super_flag_lands(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("gate.db");
        DataSource ds = SqliteDataSourceFactory.create(db);

        // 1) Simulate a pre-V19 install: migrate only through V18.
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .validateOnMigrate(false)
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO ticket(ticket_no, title, target_ref, clone_path, stage, created_at, updated_at, labels) "
                + "VALUES ('T-101','legacy','refs/heads/main','/tmp/clone','DONE','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z','[]')");
        jdbc.update("INSERT INTO ticket_restart(ticket_no, round, from_stage, reason, created_at) "
                + "VALUES ('T-101', 1, 'DONE', '需求变更，重新开启', '2026-01-02T00:00:00Z')");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ticket_restart", Integer.class));

        // 2) Upgrade to latest (V19 included).
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();

        // The renamed history table keeps the row; no to_stage on legacy rows.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ticket_stage_change", Integer.class));
        assertTrue(jdbc.queryForMap("SELECT * FROM ticket_stage_change").containsKey("to_stage"));

        TicketStageChangeRepository repo = new JdbcTicketStageChangeRepository(jdbc);
        var rows = repo.findByTicket("T-101");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isRevive());
        assertEquals(TicketStage.IN_PROGRESS, rows.get(0).effectiveToStage());
        assertEquals("需求变更，重新开启", rows.get(0).reason());
        assertTrue(repo.latestRevive("T-101").isPresent());
        assertEquals(0, repo.count("T-999"));

        // 3) Super ticket machinery works on the upgraded schema (column + partial unique index).
        JdbcTicketRepository tickets = new JdbcTicketRepository(jdbc);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO project(id, name, workspace_path, created_at, updated_at) "
                + "VALUES ('proj-a','ProjA','/tmp/ws-a','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        Ticket superT = new Ticket("T-200", "快速模式（超级工单）", "refs/heads/main",
                "/tmp/ws-a",
                null, null, "manual", "human", TicketStage.IN_PROGRESS, now, now,
                null, null, null, null, "proj-a", "d", null, java.util.List.of("快速模式"), true);
        tickets.insert(superT);
        assertTrue(tickets.findSuperByProject("proj-a").isPresent());

        // Second super ticket for the same project violates the partial unique index.
        try {
            tickets.insert(new Ticket(superT.ticketNo() + "-dup", "x", "refs/heads/main", "/tmp",
                    null, null, null, null, TicketStage.IN_PROGRESS, now, now,
                    null, null, null, null, "proj-a", null, null, java.util.List.of(), true));
            throw new AssertionError("duplicate super ticket for one project must be refused");
        } catch (org.springframework.dao.DataAccessException expected) {
            // partial unique index idx_ticket_super_project did its job
        }
    }
}
