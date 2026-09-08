package gate.adapters.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.blob.FsBlobStore;
import gate.adapters.clock.SystemClock;
import gate.domain.session.Role;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionUsage;
import gate.domain.session.TurnPart;
import gate.ports.store.ProviderRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 回合时间线（parts_blob）序列化往返：tool/thinking/text 之外，T-107 渲染修复新增的
 * steer 段（name = 被吞并 USER 行 id）必须保真落库与读回，不得降级成纯文本——
 * 历史重建按该 id 去重顶层气泡，id 丢失即插队气泡重复。
 */
class SessionPartsPersistenceTest {

    private Path root;
    private JdbcSessionRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("gate-parts-rt-");
        var ds = SqliteDataSourceFactory.create(root.resolve("gate.db"));
        SqliteDataSourceFactory.migrate(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        repo = new JdbcSessionRepository(jdbc, new FsBlobStore(root.resolve("blobs")));
        Instant now = Instant.now();
        // agent_session 对 ticket/agent_config 有外键约束（agent_config 又引用 provider）：
        // 按依赖顺序立 provider → agent_config → ticket → 会话行。
        new JdbcProviderRepository(jdbc).upsert(
                new ProviderRepository.ProviderRow("manual", "manual", "local://manual", "none", "manual", now), now);
        new JdbcAgentConfigRepository(jdbc).insert(
                new gate.domain.session.AgentConfig("cfg", "C", gate.domain.session.AgentCli.OPENCODE,
                        "manual", "m", null, List.of(), "d", false), now);
        jdbc.update("""
                INSERT INTO ticket(ticket_no, title, target_ref, clone_path, executor_provider_id,
                                   executor_model, reviewer_provider_id, reviewer_model, stage,
                                   created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "T-1", "t", "refs/heads/main", root.toString(), null, null, null, null,
                "IN_PROGRESS", now.toString(), now.toString());
        repo.insert(new gate.domain.session.Session("s-parts", "T-1", "cfg", gate.domain.session.AgentCli.OPENCODE,
                gate.domain.session.SessionStatus.ACTIVE, "cli-1", root.toString(), 0, now, null,
                SessionUsage.EMPTY, null, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        gate.adapters.io.FsUtil.deleteRecursively(root);
    }

    @Test
    void steer_and_thinking_parts_survive_roundtrip() {
        List<TurnPart> parts = List.of(
                TurnPart.text("第一段正文"),
                TurnPart.thinking("推理独白"),
                TurnPart.steer("client-9", "插队：先看测试输出"),
                TurnPart.tool("bash", "{\"command\":\"git log\"}", "commit log output"));
        repo.insertMessage(new SessionMessage("m1", "s-parts", Role.ASSISTANT, "第一段正文",
                List.of(), null, false, Instant.now(), parts, null, null, null));

        SessionMessage reloaded = repo.findMessages("s-parts").stream()
                .filter(m -> "m1".equals(m.id())).findFirst().orElseThrow();
        assertEquals(4, reloaded.parts().size(), reloaded.parts().toString());
        assertTrue(reloaded.parts().get(0).isText());
        assertTrue(reloaded.parts().get(1).isThinking(), "thinking 类型在读回时不得降级为 text");
        assertEquals("推理独白", reloaded.parts().get(1).text());
        TurnPart steer = reloaded.parts().get(2);
        assertTrue(steer.isSteer(), "steer 类型在读回时不得降级为 text");
        assertEquals("client-9", steer.name(), "USER 行 id 必须保真");
        assertEquals("插队：先看测试输出", steer.text());
        assertTrue(reloaded.parts().get(3).isTool());
    }
}
