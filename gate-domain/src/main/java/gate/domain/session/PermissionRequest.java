package gate.domain.session;

import java.util.List;
import java.util.Map;

/**
 * One pending tool-permission request surfaced by an opencode serve instance
 * (the {@code permission.asked} event, or the {@code /permission} snapshot).
 *
 * @param permissionId opencode request id
 * @param permission   the permission being requested (e.g. a tool name)
 * @param patterns     affected file patterns
 * @param always       permission categories the user may permanently allow
 * @param metadata     opaque request metadata
 * @param messageId    originating assistant message id, nullable
 * @param callId       originating tool-call id, nullable
 */
public record PermissionRequest(
        String permissionId,
        String permission,
        List<String> patterns,
        List<String> always,
        Map<String, Object> metadata,
        String messageId,
        String callId) {

    public PermissionRequest {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        always = always == null ? List.of() : List.copyOf(always);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
