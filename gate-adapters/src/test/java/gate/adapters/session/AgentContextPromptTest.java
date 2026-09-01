package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import gate.domain.project.Project;
import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import org.junit.jupiter.api.Test;

/**
 * Injected context block rendering — the「项目目标分支」line must never leak a raw "null":
 * legacy rows persisted target_ref NULL before create/update started storing the effective ref.
 */
class AgentContextPromptTest {

    private final AgentConfig config = new AgentConfig("cfg", "cfg", AgentCli.OPENCODE,
            null, null, null, List.of(), null, true);

    @Test
    void legacy_null_project_target_ref_renders_main_not_null() {
        Project project = new Project("p1", "Proj", "D:/ws/p1", null, "D:/auth/p1.git",
                null, null, List.of(), false, 0L, Instant.EPOCH, Instant.EPOCH);
        Ticket ticket = new Ticket("T-1", "标题", "refs/heads/main", null, null, null, null, null,
                TicketStage.PENDING, Instant.EPOCH, Instant.EPOCH);
        String prompt = AgentContextPrompt.compose(config, "T-1", ticket.targetRef(), ticket, project);
        assertTrue(prompt.contains("- 项目目标分支: refs/heads/main\n"), prompt);
        assertTrue(prompt.contains("- 目标分支: refs/heads/main\n"), prompt);
        assertFalse(prompt.contains("工单工作区"), "null clonePath must omit the workspace line: " + prompt);
    }

    @Test
    void ticket_clone_path_renders_as_workspace_not_project_workspace() {
        Project project = new Project("p1", "Proj", "D:/ws/p1", "refs/heads/main",
                "D:/auth/p1.git", null, null, List.of(), false, 0L, Instant.EPOCH, Instant.EPOCH);
        Ticket ticket = new Ticket("T-1", "标题", "refs/heads/main", "D:/clones/t-1", null, null,
                null, null, TicketStage.PENDING, Instant.EPOCH, Instant.EPOCH);
        String prompt = AgentContextPrompt.compose(config, "T-1", ticket.targetRef(), ticket, project);
        assertTrue(prompt.contains("- 工单工作区: D:/clones/t-1\n"),
                "the session cwd (ticket clone) is the workspace to advertise: " + prompt);
        assertFalse(prompt.contains("项目工作区"),
                "project workspace is outside the session sandbox and must not leak: " + prompt);
        assertFalse(prompt.contains("D:/ws/p1"), prompt);
    }

    @Test
    void concrete_project_target_ref_renders_verbatim() {
        Project project = new Project("p1", "Proj", "D:/ws/p1", "refs/heads/release",
                "D:/auth/p1.git", null, null, List.of(), false, 0L, Instant.EPOCH, Instant.EPOCH);
        Ticket ticket = new Ticket("T-1", "标题", "refs/heads/release", null, null, null, null, null,
                TicketStage.PENDING, Instant.EPOCH, Instant.EPOCH);
        String prompt = AgentContextPrompt.compose(config, "T-1", ticket.targetRef(), ticket, project);
        assertTrue(prompt.contains("- 项目目标分支: refs/heads/release\n"), prompt);
        assertTrue(prompt.contains("- 目标分支: refs/heads/release\n"), prompt);
    }

    @Test
    void inject_context_off_returns_only_system_prompt() {
        AgentConfig off = new AgentConfig("cfg", "cfg", AgentCli.OPENCODE, null, null,
                "custom prompt", List.of(), null, false);
        String prompt = AgentContextPrompt.compose(off, "T-1", "refs/heads/main", null, null);
        assertEquals("custom prompt", prompt.trim());
    }
}
