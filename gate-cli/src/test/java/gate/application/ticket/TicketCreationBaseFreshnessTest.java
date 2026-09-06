package gate.application.ticket;

import gate.adapters.git.GitCli;
import gate.domain.git.RepoRef;
import gate.domain.ticket.Ticket;
import gate.testkit.GateHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建票基座新鲜度（T-125 同源缺陷）：工单克隆自项目权威镜像，而人在注册工作区里提交——
 * 若建票不先把工作区基分支导入镜像，克隆就停在注册/上次同步时的旧 tip，新工单一建出来
 * 就落后 N 个提交（用户复现：新工单点「同步基座」即报"快进 N 个提交"）。
 * 修复后建票前先导入，克隆 HEAD 必须包含工作区最新提交。
 */
@Tag("slow")
class TicketCreationBaseFreshnessTest {

    private GateHarness harness;
    private GitCli git;

    @BeforeEach
    void setUp() {
        // 生产装配（GateRuntime）接线了 CloneBaseSyncer，这里必须同构，否则测不到导入路径
        harness = new GateHarness("git", true);
        git = harness.gitCli();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void clone_includes_workspace_commits_made_after_project_registration() throws Exception {
        harness.createProject("proj-a", "项目A");
        Path workspace = harness.root().resolve("ws-proj-a");
        Files.createDirectories(workspace);

        // 注册后工作区产生两次提交（模拟用户在源仓库继续开发）
        commitInWorkspace(workspace, "workspace commit 1");
        commitInWorkspace(workspace, "workspace commit 2");

        Ticket t = harness.service().createTicket(new CreateTicketCommand(
                null, "新鲜基座", "proj-a", null, null, null, null, null, null, null));

        String wsTip = git.line(RepoRef.of(workspace), "rev-parse", "HEAD").trim();
        Path clonePath = Path.of(t.clonePath());
        assertTrue(Files.exists(clonePath.resolve(".git")), "clone must be materialized");
        String cloneHead = git.line(RepoRef.of(clonePath), "rev-parse", "HEAD").trim();
        assertEquals(wsTip, cloneHead,
                "a freshly created ticket must clone the workspace's latest base tip, not a stale mirror");
    }

    @Test
    void creation_time_import_is_audited() throws Exception {
        harness.createProject("proj-a", "项目A");
        Path workspace = harness.root().resolve("ws-proj-a");
        Files.createDirectories(workspace);
        commitInWorkspace(workspace, "workspace commit");

        harness.service().createTicket(new CreateTicketCommand(
                null, "审计可见", "proj-a", null, null, null, null, null, null, null));

        String audit = Files.readString(harness.config().auditPath(), StandardCharsets.UTF_8);
        assertTrue(audit.contains("basesync.import"),
                "creation-time workspace import must be audited, audit log was: " + audit);
        assertTrue(audit.contains("\"trigger\":\"ticket_create\""), audit);
        assertTrue(audit.contains("\"project\":\"proj-a\""), audit);
    }

    @Test
    void import_failure_does_not_block_creation() {
        harness.createProject("proj-a", "项目A");
        // 工作区目录不存在（未物化）：导入必然失败，但建票必须照常成功（fail-open）
        Ticket t = harness.service().createTicket(new CreateTicketCommand(
                null, "导入失败不阻断", "proj-a", null, null, null, null, null, null, null));
        assertTrue(Files.exists(Path.of(t.clonePath()).resolve(".git")),
                "creation must proceed even when the workspace import is refused");
    }

    /** 在工作区以固定身份提交一个文件；首次调用前需把目录初始化为 main 分支仓库。 */
    private void commitInWorkspace(Path workspace, String message) throws Exception {
        if (!Files.exists(workspace.resolve(".git"))) {
            git.must(workspace, Map.of(), "init", "-b", "main");
        }
        git.must(workspace, Map.of(), "config", "user.name", "gate");
        git.must(workspace, Map.of(), "config", "user.email", "gate@localhost");
        Files.writeString(workspace.resolve("doc.md"), message + "\n", StandardCharsets.UTF_8);
        git.must(workspace, Map.of(), "add", "-A");
        git.must(workspace, harness.identityEnv(), "commit", "-m", message);
    }
}
