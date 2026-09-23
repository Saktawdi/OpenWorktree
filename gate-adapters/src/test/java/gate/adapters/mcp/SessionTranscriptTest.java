package gate.adapters.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.session.AgentCli;
import gate.domain.session.Role;
import gate.domain.session.Session;
import gate.domain.session.SessionMessage;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.domain.session.ToolCall;
import gate.domain.session.TurnPart;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code session_read} 呈现层：窗口切分、分页锚点、逐字段截断标记。
 *
 * <p>纯单元（无 DB、无 git）：renderer 只接 {@link Session} + 消息列表，这里直接构造。
 */
class SessionTranscriptTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void defaults_to_newest_window_and_reports_paging_anchors() {
        List<SessionMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(user("m-" + i, "msg " + i));
        }

        Map<String, Object> result = SessionTranscript.render(
                session(), "proj", messages, SessionTranscript.DEFAULT_LIMIT, null);

        assertEquals(30, result.get("total_messages"));
        assertEquals(20, result.get("returned"), "default window is the newest 20 messages");
        assertEquals(10L, result.get("first_index"), "window starts at the oldest of the newest");
        assertEquals(10L, result.get("next_before_index"));
        assertEquals(true, result.get("has_more"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> window = (List<Map<String, Object>>) result.get("messages");
        assertEquals(20, window.size());
        assertEquals(10L, window.get(0).get("index"), "indexes are absolute, not window-relative");
        assertEquals("msg 10", window.get(0).get("content"));
        assertEquals(29L, window.get(19).get("index"));
        assertEquals("msg 29", window.get(19).get("content"));
    }

    @Test
    void paging_backwards_walks_to_older_messages_until_the_start() {
        List<SessionMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(user("m-" + i, "msg " + i));
        }

        Map<String, Object> page1 = SessionTranscript.render(session(), null, messages, 20, null);
        int anchor = ((Long) page1.get("next_before_index")).intValue();
        Map<String, Object> page2 = SessionTranscript.render(session(), null, messages, 20, anchor);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> window = (List<Map<String, Object>>) page2.get("messages");
        assertEquals(10, window.size(), "the rest of the transcript");
        assertEquals(0L, window.get(0).get("index"));
        assertEquals(9L, window.get(9).get("index"));
        assertNull(page2.get("next_before_index"), "oldest page has no further anchor");
        assertEquals(false, page2.get("has_more"));
    }

    @Test
    void limit_is_clamped_to_the_documented_bounds() {
        assertEquals(1, SessionTranscript.clampLimit(0));
        assertEquals(1, SessionTranscript.clampLimit(-5));
        assertEquals(100, SessionTranscript.clampLimit(10_000));
        assertEquals(20, SessionTranscript.clampLimit(20));
    }

    @Test
    void long_fields_are_clipped_with_an_explicit_marker() {
        String long1 = "x".repeat(SessionTranscript.MAX_FIELD_CHARS + 25);
        SessionMessage message = new SessionMessage("m1", "s1", Role.ASSISTANT, long1,
                List.of(), null, false, T0,
                List.of(TurnPart.tool("bash", "{\"command\":\"ls\"}", long1)));

        Map<String, Object> result = SessionTranscript.render(
                session(), null, List.of(message), 20, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) result.get("messages")).get(0);

        String content = (String) rendered.get("content");
        assertTrue(content.startsWith("x".repeat(100)), "prefix survives");
        assertTrue(content.endsWith("…[truncated +25 chars]"),
                "truncation is explicit, with the dropped length: " + content.substring(content.length() - 40));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) rendered.get("parts");
        assertTrue(((String) parts.get(0).get("result_json")).endsWith("…[truncated +25 chars]"),
                "tool results are clipped on the same rule");
        assertEquals(true, rendered.get("truncated"));
    }

    @Test
    void short_fields_are_untouched_and_unmarked() {
        SessionMessage message = new SessionMessage("m1", "s1", Role.USER, "hello",
                List.of(), null, false, T0, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) SessionTranscript.render(
                session(), null, List.of(message), 20, null).get("messages")).get(0);
        assertEquals("hello", rendered.get("content"));
        assertFalse(rendered.containsKey("truncated"));
        assertNull(rendered.get("truncated"));
    }

    @Test
    void oversized_messages_omit_trailing_parts_and_count_them() {
        List<TurnPart> parts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            parts.add(TurnPart.tool("bash", "{\"n\":" + i + "}", "y".repeat(2_000)));
        }
        SessionMessage message = new SessionMessage("m1", "s1", Role.ASSISTANT, "",
                List.of(), null, false, T0, parts);

        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) SessionTranscript.render(
                session(), null, List.of(message), 20, null).get("messages")).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) rendered.get("parts");
        assertTrue(kept.size() < parts.size(), "trailing parts past the per-message cap are omitted");
        assertTrue(kept.size() > 0, "but the message keeps what fits");
        assertEquals(parts.size() - kept.size(),
                ((Number) rendered.get("omitted_parts")).intValue());
    }

    /**
     * The response budget cuts the window on the OLD side, so the newest end — what the caller
     * most wants — always survives. Messages here are part-heavy (the shape that actually makes a
     * transcript big), since single text fields are already clipped on their own.
     */
    @Test
    void response_budget_trims_the_old_side_and_keeps_the_newest() {
        List<SessionMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(partHeavy("m-" + i));
        }
        Map<String, Object> result = SessionTranscript.render(session(), null, messages, 100, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> window = (List<Map<String, Object>>) result.get("messages");
        assertTrue(window.size() < messages.size(), "the response budget cuts the window short");
        assertEquals(29L, window.get(window.size() - 1).get("index"),
                "the newest message survives — the budget trims the old side");
        assertEquals(true, result.get("budget_limited"));
        assertEquals(true, result.get("has_more"));
        assertTrue(result.get("next_before_index") instanceof Long);
    }

    /** One field-heavy message still comes back (clipped) instead of being dropped. */
    @Test
    void a_single_too_large_message_is_still_returned_with_its_truncation_markers() {
        SessionMessage huge = new SessionMessage("m-big", "s1", Role.ASSISTANT,
                "z".repeat(400_000), List.of(), null, false, T0,
                List.of(TurnPart.tool("bash", "{}", "y".repeat(400_000))));

        Map<String, Object> result = SessionTranscript.render(session(), null, List.of(huge), 100, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> window = (List<Map<String, Object>>) result.get("messages");
        assertEquals(1, window.size(), "the window always keeps at least one message");
        assertEquals(0L, window.get(0).get("index"));
        assertTrue(((String) window.get(0).get("content")).contains("…[truncated +"));
        assertEquals(true, window.get(0).get("truncated"));
        assertNull(result.get("next_before_index"));
    }

    private static SessionMessage partHeavy(String id) {
        List<TurnPart> parts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            parts.add(TurnPart.tool("bash", "{\"n\":" + i + "}", "y".repeat(2_000)));
        }
        return new SessionMessage(id, "s1", Role.ASSISTANT, "turn " + id, List.of(), null,
                false, T0, parts);
    }

    @Test
    void legacy_rows_without_a_timeline_render_their_flat_tool_calls() {
        SessionMessage legacy = new SessionMessage("m1", "s1", Role.ASSISTANT, "did things",
                List.of(new ToolCall("bash", "{\"command\":\"git status\"}", "clean")),
                null, false, T0);

        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) SessionTranscript.render(
                session(), null, List.of(legacy), 20, null).get("messages")).get(0);
        assertFalse(rendered.containsKey("parts"), "no fabricated empty timeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) rendered.get("tool_calls");
        assertEquals(1, calls.size());
        assertEquals("bash", calls.get(0).get("name"));
        assertEquals("clean", calls.get(0).get("result_json"));
    }

    @Test
    void messages_with_a_timeline_do_not_repeat_their_calls_in_the_flat_list() {
        SessionMessage message = new SessionMessage("m1", "s1", Role.ASSISTANT, "prose",
                List.of(new ToolCall("bash", "{}", "out")), null, false, T0,
                List.of(TurnPart.text("prose"), TurnPart.tool("bash", "{}", "out")));

        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) SessionTranscript.render(
                session(), null, List.of(message), 20, null).get("messages")).get(0);
        assertFalse(rendered.containsKey("tool_calls"),
                "the timeline already carries the calls, in order");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) rendered.get("parts");
        assertEquals(2, parts.size());
    }

    @Test
    void steer_parts_keep_their_user_row_id_under_name() {
        SessionMessage message = new SessionMessage("m1", "s1", Role.ASSISTANT, "",
                List.of(), null, false, T0,
                List.of(TurnPart.steer("user-row-9", "插队：先看测试输出")));

        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = ((List<Map<String, Object>>) SessionTranscript.render(
                session(), null, List.of(message), 20, null).get("messages")).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) rendered.get("parts");
        assertEquals("steer", parts.get(0).get("type"));
        assertEquals("user-row-9", parts.get(0).get("name"));
        assertEquals("插队：先看测试输出", parts.get(0).get("text"));
    }

    @Test
    void empty_transcript_renders_an_empty_window() {
        Map<String, Object> result = SessionTranscript.render(session(), null, List.of(), 20, null);
        assertEquals(0, result.get("total_messages"));
        assertEquals(0, result.get("returned"));
        assertNull(result.get("first_index"));
        assertNull(result.get("next_before_index"));
        assertEquals(false, result.get("has_more"));
    }

    @Test
    void before_index_beyond_the_end_is_clamped_instead_of_failing() {
        List<SessionMessage> messages = List.of(user("m-0", "a"), user("m-1", "b"));
        Map<String, Object> result = SessionTranscript.render(session(), null, messages, 20, 99);
        assertEquals(2, result.get("returned"));
        assertEquals(false, result.get("has_more"));
    }

    @Test
    void session_metadata_carries_the_owning_project_for_the_scope_check() {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) SessionTranscript.render(
                session(), "proj-x", List.of(), 20, null).get("session");
        assertEquals("s1", meta.get("id"));
        assertEquals("T-1", meta.get("ticket_no"));
        assertEquals("proj-x", meta.get("project_id"));
        assertEquals("ACTIVE", meta.get("status"));
        assertEquals("OPENCODE", meta.get("cli"));
    }

    private static SessionMessage user(String id, String content) {
        return new SessionMessage(id, "s1", Role.USER, content, List.of(), null, false, T0);
    }

    private static Session session() {
        return new Session("s1", "T-1", "cfg-1", AgentCli.OPENCODE, SessionStatus.ACTIVE,
                "cli-1", "C:/clones/T-1", 0, T0, null, SessionUsage.EMPTY, "会话标题", false);
    }
}
