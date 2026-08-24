package gate.web.controller;

import gate.adapters.config.TomlGateConfigLoader;
import gate.adapters.config.TomlGateConfigWriter;
import gate.adapters.mcp.McpToolRegistry;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings & MCP Status Controller.
 * Owns /api/settings/gate-toml and /api/mcp/status routes.
 */
public final class SettingsController implements WebController {

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

    public SettingsController(Path gateToml) {
        this.gateToml = gateToml == null ? null : gateToml.toAbsolutePath().normalize();
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/settings/gate-toml", this::getGateToml);
        app.put("/api/settings/gate-toml", this::updateGateToml);
        app.get("/api/mcp/status", this::getMcpStatus);
    }

    public void getGateToml(Context ctx) {
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
                continue;
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
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void updateGateToml(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
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
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void getMcpStatus(Context ctx) {
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
        ctx.status(HttpStatus.OK);
        ctx.json(body);
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
