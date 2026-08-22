package gate.domain.session;

import java.util.List;

/**
 * A reusable agent configuration (≈ multica Agent, but no Squad/Skill/Runtime) — 执行文档-后端-web §5.2.
 *
 * @param id             e.g. "claude-sonnet-default"
 * @param name           display name
 * @param cli            OPENCODE | CLAUDE
 * @param providerId     optional provider table id for a future API-backed runtime; local CLI
 *                       sessions leave this unset so the CLI owns provider selection
 * @param model          optional model override; local CLI sessions leave this unset to use the
 *                       CLI's own default configuration
 * @param systemPrompt   optional extra system prompt
 * @param extraFlags     passthrough CLI flags
 * @param description    optional description
 * @param injectContext  会话启动时把项目信息与工单信息作为系统提示词注入；缺省开启
 */
public record AgentConfig(
        String id,
        String name,
        AgentCli cli,
        String providerId,
        String model,
        String systemPrompt,
        List<String> extraFlags,
        String description,
        boolean injectContext) {

    /** Legacy shape (pre inject-context); injection defaults to on. */
    public AgentConfig(String id, String name, AgentCli cli, String providerId, String model,
                       String systemPrompt, List<String> extraFlags, String description) {
        this(id, name, cli, providerId, model, systemPrompt, extraFlags, description, true);
    }

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
        providerId = nullable(providerId);
        model = nullable(model);
        extraFlags = extraFlags == null ? List.of() : List.copyOf(extraFlags);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
