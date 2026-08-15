package gate.domain.session;

import java.util.List;

/**
 * A reusable agent configuration (≈ multica Agent, but no Squad/Skill/Runtime) — 执行文档-后端-web §5.2.
 *
 * @param id           e.g. "claude-sonnet-default"
 * @param name         display name
 * @param cli          OPENCODE | CLAUDE
 * @param providerId   provider table id
 * @param model        model name
 * @param systemPrompt optional extra system prompt
 * @param extraFlags   passthrough CLI flags
 * @param description  optional description
 */
public record AgentConfig(
        String id,
        String name,
        AgentCli cli,
        String providerId,
        String model,
        String systemPrompt,
        List<String> extraFlags,
        String description) {

    public AgentConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (cli == null) {
            throw new IllegalArgumentException("cli must not be null");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        extraFlags = extraFlags == null ? List.of() : List.copyOf(extraFlags);
    }
}
