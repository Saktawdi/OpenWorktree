package gate.application.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.error.FieldError;
import gate.domain.error.GateValidationException;
import gate.domain.ticket.Ticket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code ticket_edit} 字段解析：只校验调用方真正送来的字段、与建票同一套规则，
 * 且 <b>stage 等受门禁控制的键根本无法表达</b>。
 */
class TicketEditRequestParserTest {

    @Test
    void only_the_provided_fields_are_marked_editable() {
        var edit = TicketRequestParser.parseEdit(
                Map.of("ticket_no", "T-1", "title", "新标题", "labels", List.of("a", "b")),
                TicketRequestParser.MCP_EDIT_KEYS);

        assertTrue(edit.provides("title"));
        assertTrue(edit.provides("labels"));
        assertFalse(edit.provides("priority"), "an absent field must keep its stored value");
        assertFalse(edit.provides("description"));
        assertFalse(edit.provides("note"));
        assertEquals("新标题", edit.title());
        assertEquals(List.of("a", "b"), edit.labels());
    }

    @Test
    void ticket_no_is_addressing_not_editable_metadata() {
        var edit = TicketRequestParser.parseEdit(
                Map.of("ticket_no", "T-1", "title", "x"), TicketRequestParser.MCP_EDIT_KEYS);
        assertFalse(edit.provides("ticket_no"), "ticket_no must not count as an update");
    }

    @Test
    void absent_title_is_legal_but_a_blank_one_is_not() {
        var edit = TicketRequestParser.parseEdit(Map.of("note", "n"), TicketRequestParser.MCP_EDIT_KEYS);
        assertFalse(edit.provides("title"));
        assertNull(edit.title());

        GateValidationException blank = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(Map.of("title", "   "),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertTrue(blank.getMessage().contains("title"), blank.getMessage());
        assertFieldNamed(blank, "title");
    }

    @Test
    void a_wrong_typed_title_reports_the_type_once() {
        GateValidationException e = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(Map.of("title", 42),
                        TicketRequestParser.MCP_EDIT_KEYS));
        List<FieldError> fields = e.fieldErrors();
        assertEquals(1, fields.size(), "exactly one problem for the title: " + fields);
        assertEquals("title", fields.get(0).field());
        assertEquals("wrong JSON type", fields.get(0).problem());
    }

    @Test
    void priority_uses_the_same_enum_rule_as_creation() {
        assertEquals("P2", TicketRequestParser.parseEdit(Map.of("priority", "p2"),
                TicketRequestParser.MCP_EDIT_KEYS).priority());

        GateValidationException bad = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(Map.of("priority", "P9"),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertFieldNamed(bad, "priority");
        assertTrue(bad.getMessage().contains("P0"), bad.getMessage());
    }

    @Test
    void an_empty_priority_clears_it() {
        var edit = TicketRequestParser.parseEdit(Map.of("priority", "  "),
                TicketRequestParser.MCP_EDIT_KEYS);
        assertTrue(edit.provides("priority"), "an explicit empty string is a clear, not an omission");
        assertNull(edit.priority());
    }

    @Test
    void empty_text_clears_and_is_recorded_as_provided() {
        var edit = TicketRequestParser.parseEdit(
                Map.of("description", "  ", "note", ""), TicketRequestParser.MCP_EDIT_KEYS);
        assertTrue(edit.provides("description"));
        assertTrue(edit.provides("note"));
        assertNull(edit.description());
        assertNull(edit.note());
    }

    @Test
    void labels_are_trimmed_deduped_and_capped_like_creation() {
        var edit = TicketRequestParser.parseEdit(
                Map.of("labels", List.of(" bug ", "bug", "ui")), TicketRequestParser.MCP_EDIT_KEYS);
        assertEquals(List.of("bug", "ui"), edit.labels());

        GateValidationException tooLong = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(
                        Map.of("labels", List.of("x".repeat(Ticket.MAX_LABEL_LENGTH + 1))),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertTrue(tooLong.getMessage().contains("labels[0]"), tooLong.getMessage());

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= Ticket.MAX_LABELS; i++) {
            many.add("l" + i);
        }
        GateValidationException tooMany = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(Map.of("labels", many),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertTrue(tooMany.getMessage().contains("labels"), tooMany.getMessage());

        GateValidationException wrongType = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(Map.of("labels", "bug"),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertTrue(wrongType.getMessage().contains("wrong JSON type"), wrongType.getMessage());
    }

    @Test
    void an_empty_label_list_is_a_clear() {
        var edit = TicketRequestParser.parseEdit(Map.of("labels", List.of()),
                TicketRequestParser.MCP_EDIT_KEYS);
        assertTrue(edit.provides("labels"));
        assertEquals(List.of(), edit.labels());
    }

    @Test
    void null_values_count_as_absent_not_as_a_clear() {
        var raw = new java.util.LinkedHashMap<String, Object>();
        raw.put("note", null);
        raw.put("priority", null);
        var edit = TicketRequestParser.parseEdit(raw, TicketRequestParser.MCP_EDIT_KEYS);
        assertTrue(edit.provided().isEmpty(),
                "models fill optional properties with null; that must not wipe stored values");
    }

    /**
     * The safety property this tool exists under: a stage transition is not merely rejected, it is
     * <b>unrepresentable</b> — the key is not in the accepted set, so any attempt is reported as an
     * unknown field.
     */
    @Test
    void stage_cannot_be_expressed_at_all() {
        GateValidationException e = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(
                        Map.of("ticket_no", "T-1", "stage", "DONE"),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertFieldNamed(e, "stage");
        assertTrue(e.getMessage().contains("unknown field"), e.getMessage());
        assertTrue(e.getMessage().contains("ticket_no"), "the accepted keys are listed");
    }

    @Test
    void the_other_gate_controlled_keys_are_unrepresentable_too() {
        for (String key : List.of("stage", "project_id", "target_branch", "target_ref",
                "agent_config_id", "restart_reason", "reason")) {
            GateValidationException e = assertThrows(GateValidationException.class,
                    () -> TicketRequestParser.parseEdit(Map.of(key, "x"),
                            TicketRequestParser.MCP_EDIT_KEYS),
                    key + " must not be accepted by ticket_edit");
            assertFieldNamed(e, key);
        }
    }

    @Test
    void every_broken_field_is_reported_at_once() {
        GateValidationException e = assertThrows(GateValidationException.class,
                () -> TicketRequestParser.parseEdit(
                        Map.of("title", "  ", "priority", "P9", "labels", "nope"),
                        TicketRequestParser.MCP_EDIT_KEYS));
        assertEquals(3, e.fieldErrors().size(), "all problems in one response: " + e.fieldErrors());
    }

    private static void assertFieldNamed(GateValidationException e, String field) {
        assertTrue(e.fieldErrors().stream().anyMatch(f -> field.equals(f.field())),
                "expected a problem for '" + field + "' but got " + e.fieldErrors());
    }
}
