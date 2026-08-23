package gate.adapters.session;

import gate.domain.project.Project;
import gate.domain.session.AgentConfig;
import gate.domain.ticket.Ticket;

/**
 * Composes the effective system prompt for an agent session (注入项目/工单上下文开关).
 *
 * <p>Effective text = 可选的 AgentConfig.systemPrompt + 可选的「Gate 项目与工单上下文」块。
 * The context block renders when {@link AgentConfig#injectContext()} is on (the UI default);
 * the whole result is blank when there is nothing to inject, so callers can skip their
 * CLI-specific plumbing entirely (claude: {@code --append-system-prompt-file}; opencode:
 * first-turn message prefix). 注入的工作区是工单 clone（即会话 cwd）——项目工作区路径在会话
 * 沙箱之外，注入会误导 agent 访问 clone 之外的目录，因此绝不渲染。
 */
public final class AgentContextPrompt {

    private AgentContextPrompt() {
    }

    /**
     * @param ticketNo 工单号（会话必绑定工单）
     * @param targetRef 目标分支；null 回落 refs/heads/main
     * @param ticket 工单行，可为 null（查不到时不渲染工单明细）
     * @param project 所属项目，可为 null（未接入项目或仓库未注入时省略）；其 targetRef 为 null
     *                （存量行）时同样回落 refs/heads/main
     */
    public static String compose(AgentConfig config, String ticketNo, String targetRef,
                                 Ticket ticket, Project project) {
        StringBuilder sb = new StringBuilder();
        String sp = config.systemPrompt();
        if (sp != null && !sp.isBlank()) {
            sb.append(sp.trim()).append('\n');
        }
        if (!config.injectContext()) {
            return sb.toString();
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append("# Gate 项目与工单上下文\n\n");
        if (project != null) {
            sb.append("- 项目: ").append(project.name()).append(" (").append(project.id()).append(")\n")
              .append("- 项目目标分支: ").append(orMain(project.targetRef())).append('\n');
        }
        sb.append("- 工单号: ").append(ticketNo == null ? "" : ticketNo);
        if (ticket != null && ticket.title() != null) {
            sb.append(" · 标题: ").append(ticket.title());
        }
        sb.append('\n');
        if (ticket != null && ticket.clonePath() != null && !ticket.clonePath().isBlank()) {
            // 会话 cwd 是工单 clone；绝不注入项目工作区路径，否则 agent 会越出会话沙箱
            // 去读写 clone 之外的真实项目目录。
            sb.append("- 工单工作区: ").append(ticket.clonePath()).append('\n');
        }
        if (ticket != null) {
            if (ticket.priority() != null && !ticket.priority().isBlank()) {
                sb.append("- 优先级: ").append(ticket.priority()).append('\n');
            }
            if (ticket.description() != null && !ticket.description().isBlank()) {
                sb.append("- 需求描述: ").append(ticket.description().trim()).append('\n');
            }
            if (ticket.note() != null && !ticket.note().isBlank()) {
                sb.append("- 备注: ").append(ticket.note().trim()).append('\n');
            }
            if (ticket.labels() != null && !ticket.labels().isEmpty()) {
                sb.append("- 标签: ").append(String.join(", ", ticket.labels())).append('\n');
            }
        }
        sb.append("- 目标分支: ").append(orMain(targetRef)).append('\n')
          .append("- 你无权 push 到权威库；预提审请调 presubmit_create MCP 工具\n")
          .append("- tree_hash 约定: 审核锚定不可变 tree，修改后需重新预提审\n");
        return sb.toString();
    }

    /** Null/blank refs (legacy NULL project rows) render the effective default, not "null". */
    private static String orMain(String ref) {
        return ref == null || ref.isBlank() ? "refs/heads/main" : ref;
    }
}
