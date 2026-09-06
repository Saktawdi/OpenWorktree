package gate.application.ticket;

import gate.domain.error.FieldError;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateValidationException;
import gate.domain.ticket.Ticket;
import gate.domain.ticket.TicketStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One parser for the raw ticket-create request (web JSON body <em>and</em> MCP
 * {@code ticket_create} arguments), so the two entry points can never drift (T-108).
 *
 * <p>Every <em>static</em> rule lives here — required {@code title}, JSON types, enum ranges
 * (priority/stage/labels), ticket-no and branch segment formats — and is reported as one
 * {@link GateValidationException} carrying <em>all</em> broken fields at once (structured
 * {@link FieldError}s), instead of failing one field at a time with prose. State-dependent rules
 * (duplicate ticket_no, unknown project, missing auth repo) stay in
 * {@link TicketCreationHandler} and surface as plain {@code GateException}(USAGE), so callers can
 * tell "fix your request" (validation) apart from "the current state prevents this" (domain).
 *
 * <p>JSON {@code null} values are treated as absent (models routinely fill optional schema
 * properties with null). Only {@code String} values are accepted for text fields — a number or
 * object is a structured "wrong JSON type" problem, never a silent coercion.
 *
 * <p>{@link #normalize(CreateTicketCommand)} re-applies the same field rules to a typed command,
 * so {@link TicketCreationHandler} stays safe even when a caller builds a command without going
 * through {@link #parse} — both entry points and the handler share one rule implementation.
 */
public final class TicketRequestParser {

    /** Keys the web console may send (stage, target_ref alias and agent_config binding are web-only). */
    public static final Set<String> WEB_KEYS = Set.of(
            "title", "ticket_no", "project_id", "target_branch", "target_ref", "stage",
            "priority", "description", "note", "labels", "agent_config_id");

    /** Keys the MCP {@code ticket_create} tool schema declares (kept in sync with McpToolRegistry). */
    public static final Set<String> MCP_KEYS = Set.of(
            "title", "ticket_no", "project_id", "target_branch",
            "priority", "description", "note", "labels");

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private TicketRequestParser() {
    }

    /**
     * Validates the raw request (JSON types + field rules) and returns the normalized command.
     *
     * @param raw         parsed JSON object from the entry point (Jackson for web, MiniJson for MCP)
     * @param allowedKeys accepted field names ({@code null} = any key); unknown keys are reported
     * @throws GateValidationException listing every broken field when validation fails
     */
    public static CreateTicketCommand parse(Map<String, Object> raw, Set<String> allowedKeys) {
        List<FieldError> problems = new ArrayList<>();

        for (String key : new TreeSet<>(raw.keySet())) {
            if (allowedKeys != null && !allowedKeys.contains(key)) {
                problems.add(new FieldError(key, "unknown field",
                        "one of: " + sorted(allowedKeys), null));
            }
        }

        // Title: absent / blank / non-string handled here and in finish() — exactly once each.
        String title = null;
        boolean titleShapeChecked = false;
        Object rawTitle = raw.get("title");
        if (rawTitle == null) {
            // absent → finish() reports "missing required parameter"
            titleShapeChecked = false;
        } else if (rawTitle instanceof String s) {
            // blank → finish() reports; otherwise accepted
            titleShapeChecked = false;
            title = s;
        } else {
            problems.add(new FieldError("title", "wrong JSON type", "string", jsonType(rawTitle)));
            titleShapeChecked = true; // finish() must not add a second (missing) title problem
        }

        String ticketNo = stringValue(raw, "ticket_no", problems);
        String projectId = stringValue(raw, "project_id", problems);
        String priority = stringValue(raw, "priority", problems);
        String stage = stringValue(raw, "stage", problems);
        String description = stringValue(raw, "description", problems);
        String note = stringValue(raw, "note", problems);
        String agentConfigId = stringValue(raw, "agent_config_id", problems);
        String targetBranch = stringValue(raw, "target_branch", problems);
        if (targetBranch == null && allowedKeys != null && allowedKeys.contains("target_ref")) {
            targetBranch = stringValue(raw, "target_ref", problems);
        }
        List<String> labels = labelsValue(raw, problems);

        return finish(ticketNo, title, projectId, targetBranch, stage, priority,
                description, note, labels, agentConfigId, problems, titleShapeChecked);
    }

    /**
     * Re-validates a typed command against the same field rules and returns the normalized form.
     * Used by {@link TicketCreationHandler} so callers that bypass {@link #parse} (tests, wiring)
     * still cannot create an invalid ticket.
     */
    public static CreateTicketCommand normalize(CreateTicketCommand command) {
        return finish(command.ticketNo(), command.title(), command.projectId(),
                command.targetBranch(), command.stage(), command.priority(),
                command.description(), command.note(), command.labels(),
                command.agentConfigId(), new ArrayList<>(), false);
    }

    /** Shared tail: field rules over normalized strings, then command assembly or throw. */
    private static CreateTicketCommand finish(String ticketNoRaw, String titleRaw, String projectIdRaw,
                                              String branchRaw, String stageRaw, String priorityRaw,
                                              String descriptionRaw, String noteRaw,
                                              List<String> labelsRaw, String agentConfigIdRaw,
                                              List<FieldError> problems, boolean titleShapeChecked) {
        String title = titleShapeChecked ? null : titleField(titleRaw, problems);
        String priority = priorityField(priorityRaw, problems);
        String stage = stageField(stageRaw, problems);
        String ticketNo = normalize(ticketNoRaw);
        String branchSegment = branchField(ticketNo, branchRaw, problems);
        List<String> labels = labelsField(labelsRaw, problems);

        if (!problems.isEmpty()) {
            throw new GateValidationException(GateErrorCode.USAGE, "invalid ticket_create request", problems);
        }
        return new CreateTicketCommand(
                ticketNo,
                title,
                normalize(projectIdRaw),
                branchSegment,
                stage,
                priority,
                normalize(descriptionRaw),
                normalize(noteRaw),
                labels,
                normalize(agentConfigIdRaw));
    }

    // --- shared field rules ---

    /** Required, non-blank title; returns the trimmed value. */
    private static String titleField(String raw, List<FieldError> problems) {
        if (raw == null) {
            problems.add(new FieldError("title", "missing required parameter", "non-blank string", null));
            return null;
        }
        if (raw.isBlank()) {
            problems.add(new FieldError("title", "must not be blank", "non-blank string", null));
            return null;
        }
        return raw.trim();
    }

    /** One of {@code Ticket.PRIORITIES}, case-insensitive; null stays null. */
    private static String priorityField(String raw, List<FieldError> problems) {
        String n = normalizeUpper(raw);
        if (n != null && !Ticket.PRIORITIES.contains(n)) {
            problems.add(new FieldError("priority", "invalid value",
                    "one of " + Ticket.PRIORITIES + " or null", raw));
        }
        return n;
    }

    /** Any {@link TicketStage} name (case-insensitive); only queue-entry stages are creatable. */
    private static String stageField(String raw, List<FieldError> problems) {
        String n = normalizeUpper(raw);
        if (n == null) {
            return null;
        }
        TicketStage parsed;
        try {
            parsed = TicketStage.valueOf(n);
        } catch (IllegalArgumentException e) {
            problems.add(new FieldError("stage", "no such stage",
                    "one of: " + stageNames(), raw));
            return null;
        }
        if (parsed != TicketStage.PENDING && parsed != TicketStage.IN_PROGRESS) {
            problems.add(new FieldError("stage", "stage not allowed at creation",
                    "PENDING or IN_PROGRESS", raw));
            return null;
        }
        return n;
    }

    /**
     * Branch segment validation: with no explicit branch the ticket number becomes the branch
     * name, so it must then be a valid single segment (mirrors the previous default resolution).
     */
    private static String branchField(String ticketNo, String targetBranchRaw,
                                      List<FieldError> problems) {
        String segment = normalizeBranch(targetBranchRaw);
        if (segment == null) {
            if (ticketNo != null && !isValidSegment(ticketNo)) {
                problems.add(new FieldError("ticket_no", "invalid ticket number",
                        "[A-Za-z0-9._-]+ (single segment, max 80 chars)", ticketNo));
            }
        } else if (!isValidSegment(segment)) {
            problems.add(new FieldError("target_branch", "invalid branch name",
                    "[A-Za-z0-9._-]+ (single segment, max 80 chars, no slash, no .lock suffix)",
                    targetBranchRaw));
        }
        return segment;
    }

    /**
     * Trimmed, de-duplicated, size-capped labels; blank entries dropped, per-index problems.
     * A {@code null} slot means the raw JSON item at that index was not a string — its type
     * problem was already reported by {@link #labelsValue}, and the index stays aligned with the
     * original request array so semantic problems point at the same element.
     */
    private static List<String> labelsField(List<String> rawItems, List<FieldError> problems) {
        if (rawItems == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < rawItems.size(); i++) {
            String item = rawItems.get(i);
            if (item == null) {
                continue; // non-string JSON item — type problem already reported by labelsValue
            }
            String label = item.trim();
            if (label.isEmpty() || labels.contains(label)) {
                continue;
            }
            if (label.length() > Ticket.MAX_LABEL_LENGTH) {
                problems.add(new FieldError("labels[" + i + "]", "too long",
                        "at most " + Ticket.MAX_LABEL_LENGTH + " chars", label));
                continue;
            }
            labels.add(label);
        }
        if (labels.size() > Ticket.MAX_LABELS) {
            problems.add(new FieldError("labels", "too many",
                    "at most " + Ticket.MAX_LABELS + " labels", String.valueOf(labels.size())));
        }
        return labels;
    }

    // --- JSON shape helpers (parse only) ---

    private static List<String> labelsValue(Map<String, Object> raw, List<FieldError> problems) {
        Object v = raw.get("labels");
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> list)) {
            problems.add(new FieldError("labels", "wrong JSON type", "array of strings", jsonType(v)));
            return null;
        }
        // Index-aligned list: non-string items become null slots (their type problem is added
        // here), so labelsField semantics keep pointing at the original array index.
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String s) {
                strings.add(s);
            } else {
                problems.add(new FieldError("labels[" + i + "]", "wrong JSON type",
                        "string", jsonType(item)));
                strings.add(null);
            }
        }
        return strings;
    }

    private static String stringValue(Map<String, Object> raw, String key, List<FieldError> problems) {
        Object v = raw.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        problems.add(new FieldError(key, "wrong JSON type", "string", jsonType(v)));
        return null;
    }

    /** JSON value type name for the {@code actual} field of a type problem. */
    private static String jsonType(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Map<?, ?>) {
            return "object";
        }
        if (v instanceof List<?>) {
            return "array";
        }
        return v.getClass().getSimpleName();
    }

    // --- normalization helpers ---

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String normalizeUpper(String raw) {
        String n = normalize(raw);
        return n == null ? null : n.toUpperCase(Locale.ROOT);
    }

    /** Strips a {@code refs/heads/} prefix then trims; blank stays null. */
    private static String normalizeBranch(String raw) {
        String n = normalize(raw);
        if (n == null) {
            return null;
        }
        if (n.startsWith("refs/heads/")) {
            n = n.substring("refs/heads/".length());
        }
        return n.isBlank() ? null : n;
    }

    /** Single git ref segment: no slash, no {@code .} / {@code ..}, no trailing {@code .lock}. */
    private static boolean isValidSegment(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.endsWith(".lock")
                || name.length() > 80) {
            return false;
        }
        return SEGMENT.matcher(name).matches();
    }

    private static String stageNames() {
        StringBuilder sb = new StringBuilder();
        for (TicketStage s : TicketStage.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.name());
        }
        return sb.toString();
    }

    private static String sorted(Set<String> keys) {
        return String.join(", ", new TreeSet<>(keys));
    }
}
