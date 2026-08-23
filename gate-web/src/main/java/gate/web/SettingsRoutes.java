package gate.web;

import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.config.TomlGateConfigWriter;
import gate.adapters.mcp.McpToolRegistry;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置中心端点（V5 web console 设置中心）：gate.toml 参数视图/写回 + gate MCP 工具状态。
 *
 * <p>{@code GET /api/config} 仍是脱敏只读视图；这里的可编辑目录与 {@link TomlGateConfigWriter} 的
 * KEY_TYPES/NON_EDITABLE 同源（经其 public 目录方法引用），展示与写回校验不会漂移。gate.toml 修改
 * 仅落盘，运行中的进程持有不可变 {@code GateConfig} record，因此写回响应恒带 {@code restart_required=true}；
 * fail-closed 校验（临时文件经 loader 完整加载）与 {@code .bak} 备份由 writer 负责。
 */
public final class SettingsRoutes {

    /** 展示分区与标题，固定顺序（展示顺序独立于目录声明顺序）。 */
    private static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("", "基本"),
            new SectionSpec("gate_identity", "提交身份"),
            new SectionSpec("web", "Web 控制台"),
            new SectionSpec("session", "会话编排"),
            new SectionSpec("policy", "审查策略"),
            new SectionSpec("engine", "审查引擎"),
            new SectionSpec("agent", "智能体默认"));

    private record SectionSpec(String section, String title) {
    }

    /**
     * 文件未写该键时的生效回退值，与 {@link TomlGateConfigLoader} build() 的 getOrDefault 表一致
     * （必填键无默认概念，缺省即 null）。
     */
    private static final Map<String, Object> DEFAULTS = buildDefaults();

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target_ref_whitelist", List.of("refs/heads/main"));
        m.put("gate_identity.name", "gate");
        m.put("gate_identity.email", "gate@localhost");
        m.put("gate_identity.date", "1700000000 +0000");
        m.put("policy.strictness", "BLOCKER_ONLY");
        m.put("policy.require_coverage", Boolean.TRUE);
        m.put("policy.max_diff_bytes", 2_000_000L);
        m.put("policy.max_diff_lines", 20_000L);
        m.put("policy.engine_accept_degraded", Boolean.FALSE);
        m.put("engine.timeout_seconds", 120L);
        m.put("web.bind", "127.0.0.1");
        m.put("web.port", 4097L);
        m.put("web.allowed_origins", List.of("127.0.0.1", "localhost"));
        m.put("session.port_range_min", 49152L);
        m.put("session.port_range_max", 65535L);
        m.put("session.default_cli", "claude");
        m.put("session.start_timeout_seconds", 60L);
        return Map.copyOf(m);
    }

    private final Path gateToml;
    private final TomlGateConfigLoader loader = new TomlGateConfigLoader();
    private final TomlGateConfigWriter writer = new TomlGateConfigWriter();

    SettingsRoutes(Path gateToml) {
        this.gateToml = gateToml == null ? null : gateToml.toAbsolutePath().normalize();
    }

    /** GET /api/settings/gate-toml — 全键目录的分组原始值（value=null 表示文件未写，前端展示 default）。 */
    public ApiRoutes.Response gateTomlView() {
        Map<String, Object> raw = loader.rawValues(requireToml());
        List<Map<String, Object>> sections = new ArrayList<>();
        for (SectionSpec spec : SECTIONS) {
            List<Map<String, Object>> keys = new ArrayList<>();
            for (String key : TomlGateConfigWriter.knownKeys()) {
                if (!sectionOf(key).equals(spec.section())) {
                    continue;
                }
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("key", key);
                k.put("value", raw.get(key));
                k.put("type", TomlGateConfigWriter.typeName(key));
                k.put("editable", TomlGateConfigWriter.isEditable(key));
                k.put("default", DEFAULTS.get(key));
                keys.add(k);
            }
            if (keys.isEmpty()) {
                continue; // 目录中该分区暂无键（防御，当前每个分区都有键）
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("section", spec.section());
            s.put("title", spec.title());
            s.put("keys", keys);
            sections.add(s);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toml_path", requireToml().toString());
        body.put("restart_required", true);
        body.put("sections", sections);
        return new ApiRoutes.Response(200, body);
    }

    /** PUT /api/settings/gate-toml — 只提交变更键；值 null = 删除该键回退默认。校验失败原文件不动。 */
    public ApiRoutes.Response gateTomlUpdate(Map<String, Object> req) {
        Object rawUpdates = req.get("updates");
        if (!(rawUpdates instanceof Map<?, ?> map)) {
            throw new GateException(GateErrorCode.USAGE, "updates must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> updates = (Map<String, Object>) map;
        writer.write(requireToml(), updates);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("updated", new ArrayList<>(updates.keySet()));
        body.put("restart_required", true);
        return new ApiRoutes.Response(200, body);
    }

    /** GET /api/mcp/status — gate MCP 工具按会话注入 agent CLI 的状态与工具清单。 */
    public ApiRoutes.Response mcpStatus() {
        List<Map<String, Object>> tools = new ArrayList<>();
        int agentCount = 0;
        int humanCount = 0;
        for (McpToolRegistry.ToolDef t : McpToolRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("domain", t.domain());
            m.put("description", t.description());
            tools.add(m);
            if (McpToolRegistry.AGENT_DOMAIN.equals(t.domain())) {
                agentCount++;
            } else if (McpToolRegistry.HUMAN_DOMAIN.equals(t.domain())) {
                humanCount++;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provisioning", gateToml != null ? "enabled" : "disabled");
        body.put("transport", "stdio");
        body.put("serve_command", "gate mcp serve --config <gate.toml>");
        body.put("cli_integration", List.of(
                Map.of("cli", "claude", "mechanism", "--mcp-config mcpServers 块（--strict-mcp-config）"),
                Map.of("cli", "opencode", "mechanism", "OPENCODE_CONFIG 的 mcp 块（与全局配置按键合并）")));
        body.put("tools", tools);
        body.put("agent_tool_count", agentCount);
        body.put("human_tool_count", humanCount);
        return new ApiRoutes.Response(200, body);
    }

    private Path requireToml() {
        if (gateToml == null) {
            throw new GateException(GateErrorCode.USAGE, "gate.toml path unknown");
        }
        return gateToml;
    }

    private static String sectionOf(String fullKey) {
        int dot = fullKey.indexOf('.');
        return dot < 0 ? "" : fullKey.substring(0, dot);
    }
}
