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

    /** 设置中心分区顺序（高频可调项在前）。空 section = 顶层键，收尾作为「高级设置」。 */
    private static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("web", "Web 控制台"),
            new SectionSpec("engine", "审查引擎"),
            new SectionSpec("policy", "审查策略"),
            new SectionSpec("agent", "智能体默认"),
            new SectionSpec("gate_identity", "提交身份"),
            new SectionSpec("session", "会话编排"),
            new SectionSpec("", "高级设置"));

    private record SectionSpec(String section, String title) {
    }

    private static final Map<String, Object> DEFAULTS = buildDefaults();

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target_ref_whitelist", List.of("refs/heads/main"));
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
        m.put("session.default_cli", "opencode");
        m.put("session.start_timeout_seconds", 60L);
        return Map.copyOf(m);
    }

    /**
     * 设置中心每个键的人性化元数据（取值约束的唯一事实来源，与 {@link GateConfig} 的域校验一致）：
     * {@code options} 非空 → 前端渲染下拉框（有枚举取值的键不再暴露自由输入框）；
     * {@code min}/{@code max} → 数字输入的范围约束；{@code hint} → 表单说明文字。
     * engine.provider_id / model 与 agent.default_provider / model 的选项依赖运行期 LLM Provider
     * 列表，静态无法下发，仅给 hint，由前端从「LLM 设置」数据动态计算。
     */
    private record KeyMeta(String hint, List<Map<String, String>> options, Long min, Long max) {

        static KeyMeta hint(String hint) {
            return new KeyMeta(hint, null, null, null);
        }

        /** value/label 成对给出。 */
        static KeyMeta select(String hint, String... valueLabelPairs) {
            if (valueLabelPairs.length % 2 != 0) {
                throw new IllegalArgumentException("value/label pairs must be even");
            }
            List<Map<String, String>> opts = new ArrayList<>();
            for (int i = 0; i < valueLabelPairs.length; i += 2) {
                opts.add(Map.of("value", valueLabelPairs[i], "label", valueLabelPairs[i + 1]));
            }
            return new KeyMeta(hint, List.copyOf(opts), null, null);
        }

        static KeyMeta range(String hint, Long min, Long max) {
            return new KeyMeta(hint, null, min, max);
        }
    }

    private static final Map<String, KeyMeta> KEY_META = buildKeyMeta();

    /** 未设置键输入框的 placeholder（如实描述运行期行为，不放误导性的静态值）。 */
    private static final Map<String, String> PLACEHOLDERS = buildPlaceholders();

    private static Map<String, String> buildPlaceholders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("gate_identity.name", "默认：本机 git user.name，无配置时回退 gate");
        m.put("gate_identity.email", "默认：本机 git user.email，无配置时回退 gate@localhost");
        m.put("gate_identity.date", "默认：发布时刻（真实时间）");
        return Map.copyOf(m);
    }

    private static Map<String, KeyMeta> buildKeyMeta() {
        Map<String, KeyMeta> m = new LinkedHashMap<>();
        // —— 基本 ——
        m.put("schema_version", KeyMeta.hint("配置结构版本，当前必须为 2，随 schema 升级"));
        m.put("project", KeyMeta.hint("项目标识，用于门禁展示与审计"));
        m.put("auth_repo", KeyMeta.hint("门禁认证仓路径，初始化时生成"));
        m.put("clones_root", KeyMeta.hint("工单 clone 的存放根目录，初始化时生成"));
        m.put("target_ref_whitelist",
                KeyMeta.hint("门禁只允许写入这些分支；每项必须是完整的 refs/heads/… 引用名，且至少一项"));
        m.put("gate_home", KeyMeta.hint("gate 数据主目录，初始化时生成"));
        m.put("approvals_dir", KeyMeta.hint("人工审批文件目录，初始化时生成"));
        m.put("db_path", KeyMeta.hint("SQLite 数据库文件路径，初始化时生成"));
        m.put("blob_root", KeyMeta.hint("blob 存储根目录，初始化时生成"));
        m.put("audit_path", KeyMeta.hint("审计流水文件路径，初始化时生成"));
        m.put("locks_dir", KeyMeta.hint("锁文件目录，初始化时生成"));
        m.put("index_dir", KeyMeta.hint("索引目录，初始化时生成"));
        // —— 提交身份 ——
        m.put("gate_identity.name", KeyMeta.hint("门禁提交者的 git 身份名；不写则回退 gate"));
        m.put("gate_identity.email", KeyMeta.hint("门禁提交者的 git 邮箱；不写则回退 gate@localhost"));
        m.put("gate_identity.date", KeyMeta.hint("门禁提交时间，git 风格：秒级时间戳 + 时区；不写则用发布时刻"));
        // —— 审查策略 ——
        m.put("policy.strictness", KeyMeta.select(
                "BLOCKER 驳回；WARNING 默认放行，切到 BLOCKER_AND_WARNING 后一并驳回",
                "BLOCKER_ONLY", "BLOCKER_ONLY（只在 BLOCKER 时驳回）",
                "BLOCKER_AND_WARNING", "BLOCKER_AND_WARNING（外加 WARNING 严拒）"));
        m.put("policy.require_coverage", KeyMeta.hint("变更缺少测试覆盖时是否驳回"));
        m.put("policy.max_diff_bytes", KeyMeta.range("单轮审查允许的最大 diff 字节数，超出直接驳回", 1L, null));
        m.put("policy.max_diff_lines", KeyMeta.range("单轮审查允许的最大 diff 行数，超出直接驳回", 1L, null));
        m.put("policy.engine_accept_degraded", KeyMeta.hint("审查引擎降级（超时/不可用）时是否放行；默认关闭（fail-closed）"));
        // —— 审查引擎 ——
        m.put("engine.kind", KeyMeta.select("目前只有 gate 内建审查引擎一种，其余值会被拒绝启动",
                "gate-engine", "gate-engine（gate 内建审查引擎）"));
        m.put("engine.timeout_seconds", KeyMeta.range("单轮审查总墙钟上限（秒）", 1L, null));
        m.put("engine.provider_id", KeyMeta.hint("审查引擎使用的 LLM Provider，选项来自「LLM 设置」"));
        m.put("engine.model", KeyMeta.hint("审查使用的模型，选项跟随所选 Provider"));
        m.put("engine.idle_timeout_seconds",
                KeyMeta.range("流式读帧的空闲失效窗口（秒），窗口内无数据帧即判停流", 1L, null));
        m.put("engine.max_tokens", KeyMeta.range("注入请求体的输出 token 上限；不设置则交给上游默认", 1L, null));
        // —— Web 控制台 ——
        m.put("web.bind", KeyMeta.select(
                "控制台只允许绑定本机回环地址；0.0.0.0 / :: 会被拒绝启动（本机驱动，不暴露网络）",
                "127.0.0.1", "127.0.0.1（IPv4 回环）",
                "localhost", "localhost（本机主机名）",
                "::1", "::1（IPv6 回环）"));
        m.put("web.port", KeyMeta.range("控制台 HTTP 端口，0 表示随机分配", 0L, 65535L));
        m.put("web.allowed_origins", KeyMeta.hint("允许的 Host/Origin 白名单，防 DNS 重绑定；不能为空"));
        m.put("web.human_token_file", KeyMeta.hint("HUMAN 域引导令牌的写入位置（相对 gate_home 或绝对路径）"));
        // —— 会话编排 ——
        m.put("session.port_range_min", KeyMeta.range("会话端口池下界，须不大于上界", 1L, 65535L));
        m.put("session.port_range_max", KeyMeta.range("会话端口池上界", 1L, 65535L));
        m.put("session.default_cli", KeyMeta.select("新建会话默认启动的 CLI",
                "opencode", "opencode（opencode serve 会话）",
                "claude", "claude（Claude Code 无头会话）"));
        m.put("session.default_agent_config", KeyMeta.hint("新建会话默认使用的 agent 配置名"));
        m.put("session.start_timeout_seconds", KeyMeta.range("会话冷启动健康等待上限（秒）", 5L, 900L));
        // —— 智能体默认 ——
        m.put("agent.default_model", KeyMeta.hint("智能体会话默认模型，选项跟随所选 Provider"));
        m.put("agent.default_provider", KeyMeta.hint("智能体会话默认 Provider，选项来自「LLM 设置」"));
        m.put("agent.context_template", KeyMeta.hint("智能体上下文模板文件路径（相对 gate_home 或绝对路径）"));
        return m;
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
                // 只读键不放出来：初始化生成的路径类键（gate_home/db_path/…）与 schema_version
                // 由系统管理，设置中心只展示可编辑项。
                if (!TomlGateConfigWriter.isEditable(key)) {
                    continue;
                }
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("key", shortKeyOf(key));
                k.put("value", raw.get(key));
                k.put("type", TomlGateConfigWriter.typeName(key));
                k.put("editable", TomlGateConfigWriter.isEditable(key));
                k.put("default", DEFAULTS.get(key));
                KeyMeta meta = KEY_META.get(key);
                if (meta != null) {
                    if (meta.options() != null) {
                        k.put("options", meta.options());
                    }
                    if (meta.min() != null) {
                        k.put("min", meta.min());
                    }
                    if (meta.max() != null) {
                        k.put("max", meta.max());
                    }
                    k.put("hint", meta.hint());
                }
                String placeholder = PLACEHOLDERS.get(key);
                if (placeholder != null) {
                    k.put("placeholder", placeholder);
                }
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
                Map.of("cli", "opencode", "mechanism", "OPENCODE_CONFIG 的 mcp 块（与全局配置按键合并）"),
                Map.of("cli", "claude", "mechanism", "--mcp-config mcpServers 块（--strict-mcp-config）")));
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

    /** 目录键是全名（如 engine.provider_id）；视图按 section + 分区内短键下发，避免前端二次拼接前缀。 */
    private static String shortKeyOf(String fullKey) {
        int dot = fullKey.indexOf('.');
        return dot < 0 ? fullKey : fullKey.substring(dot + 1);
    }
}
