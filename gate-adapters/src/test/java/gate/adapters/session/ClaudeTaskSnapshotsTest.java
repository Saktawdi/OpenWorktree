package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.TurnPart;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V24 claude 任务 journal 累加器：TaskCreate 追加（乐观 id max+1 / result 权威 id）、
 * TaskUpdate 按 id 全字段应用、未知 id 忽略、TaskList 只读不产生事件、全量重放。
 */
class ClaudeTaskSnapshotsTest {

    @Test
    void writeToolRecognition_covers_create_update_only() {
        assertTrue(ClaudeTaskSnapshots.isWriteTool("TaskCreate"));
        assertTrue(ClaudeTaskSnapshots.isWriteTool("TaskUpdate"));
        assertFalse(ClaudeTaskSnapshots.isWriteTool("TaskList"), "read-only tool must not journal");
        assertFalse(ClaudeTaskSnapshots.isWriteTool("todowrite"));
        assertFalse(ClaudeTaskSnapshots.isWriteTool(null));
    }

    @Test
    void liveCreateAssignsOptimisticMaxPlusOne() {
        // live：result 未到，空 journal 首个任务拿 id 1（max+1）。
        String first = ClaudeTaskSnapshots.applyJson("[]", "TaskCreate",
                "{\"subject\":\"a\"}", null);
        assertTrue(first.contains("\"id\":1"), first);

        // 已有 id 1、4 时新任务拿 5。
        String second = ClaudeTaskSnapshots.applyJson(
                "[{\"id\":1,\"subject\":\"x\",\"status\":\"pending\"},{\"id\":4,\"subject\":\"y\",\"status\":\"pending\"}]",
                "TaskCreate", "{\"subject\":\"b\"}", null);
        assertTrue(second.contains("\"id\":5"), second);
    }

    @Test
    void authoritativeResultIdBeatsOptimistic() {
        String applied = ClaudeTaskSnapshots.applyJson("[]", "TaskCreate",
                "{\"subject\":\"a\"}", "Task #7 created successfully: a");
        assertTrue(applied.contains("\"id\":7"), applied);
    }

    @Test
    void createdTaskIdParsesRealWorldResultShapes() {
        assertEquals(12, ClaudeTaskSnapshots.createdTaskId("Task #12 created successfully: do things"));
        assertEquals(3, ClaudeTaskSnapshots.createdTaskId("Task #3 created successfully: x"));
        assertNull(ClaudeTaskSnapshots.createdTaskId("Updated task #3 status"));
        assertNull(ClaudeTaskSnapshots.createdTaskId(null));
        assertNull(ClaudeTaskSnapshots.createdTaskId("created successfully without id"));
    }

    @Test
    void updateAppliesAllPresentFields_andStringTaskId() {
        String created = ClaudeTaskSnapshots.applyJson("[]", "TaskCreate",
                "{\"subject\":\"old\",\"description\":\"d\"}", "Task #2 created successfully: old");
        // taskId 是字符串（真实归档会话实测形状）。
        String updated = ClaudeTaskSnapshots.applyJson(created, "TaskUpdate",
                "{\"taskId\":\"2\",\"status\":\"completed\",\"activeForm\":\"finishing\"}", null);
        assertTrue(updated.contains("\"status\":\"completed\""), updated);
        assertTrue(updated.contains("\"activeForm\":\"finishing\""), updated);
        assertTrue(updated.contains("\"description\":\"d\""), "untouched fields survive: " + updated);
    }

    @Test
    void updateIgnoresUnknownIdAndBadInput() {
        String created = ClaudeTaskSnapshots.applyJson("[]", "TaskCreate",
                "{\"subject\":\"a\"}", "Task #1 created successfully: a");
        // 未知 id：忽略（返回 null = 不覆盖现有 journal）。
        assertNull(ClaudeTaskSnapshots.applyJson(created, "TaskUpdate",
                "{\"taskId\":\"99\",\"status\":\"completed\"}", null));
        // 参数不可解析：忽略。
        assertNull(ClaudeTaskSnapshots.applyJson(created, "TaskCreate", "not-json", null));
        // 非 claude 任务工具：永远 no-op。
        assertNull(ClaudeTaskSnapshots.applyJson(created, "TaskList", "{}", null));
        assertNull(ClaudeTaskSnapshots.applyJson(created, "todowrite", "{}", null));
    }

    @Test
    void replayIsDeterministicProjectionOfHistory() {
        // B 方案：整表 = 历史事件流的纯投影。含幽灵修复语义——重放只认历史里的真实事件。
        List<TurnPart> parts = List.of(
                TurnPart.tool("Bash", "{\"command\":\"ls\"}", "a\nb"),
                TurnPart.tool("TaskCreate", "{\"subject\":\"s1\",\"description\":\"d1\"}",
                        "Task #1 created successfully: s1"),
                TurnPart.tool("TaskCreate", "{\"subject\":\"s2\"}",
                        "Task #2 created successfully: s2"),
                TurnPart.tool("TaskUpdate", "{\"taskId\":\"1\",\"status\":\"in_progress\"}",
                        "Updated task #1 status"),
                TurnPart.tool("TaskUpdate", "{\"taskId\":\"2\",\"status\":\"completed\",\"subject\":\"s2-renamed\"}",
                        "Updated task #2 status"),
                TurnPart.tool("TaskList", "{}", "[]"),
                TurnPart.tool("TaskUpdate", "{\"taskId\":\"1\",\"status\":\"completed\"}",
                        "Updated task #1 status"));
        String replayed = ClaudeTaskSnapshots.replayJson(parts);
        assertTrue(replayed.contains("\"id\":1"), replayed);
        assertTrue(replayed.contains("\"id\":2"), replayed);
        // task 1 经 pending → in_progress → completed，终态取最后一次更新（投影语义）。
        assertTrue(replayed.contains("\"subject\":\"s1\",\"description\":\"d1\",\"status\":\"completed\""), replayed);
        assertTrue(replayed.contains("s2-renamed"), "update applies subject renames: " + replayed);
        assertFalse(replayed.contains("TaskList"), "read-only tool is not an event: " + replayed);
    }

    @Test
    void replayWithoutTaskEventsReturnsNull() {
        assertNull(ClaudeTaskSnapshots.replayJson(List.of(
                TurnPart.tool("Bash", "{\"command\":\"ls\"}", "ok"),
                TurnPart.thinking("hmm"))));
        assertNull(ClaudeTaskSnapshots.replayJson(List.of()));
    }
}
