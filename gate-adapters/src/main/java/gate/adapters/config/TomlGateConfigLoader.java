package gate.adapters.config;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.policy.Policy;
import gate.domain.publish.CommitIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code gate.toml} into a {@link GateConfig} (架构落地执行文档 §10.1).
 *
 * <p>fail-closed applies to configuration too: this is a deliberately small, dependency-free parser
 * for the flat subset the gate uses (top-level {@code key = value} plus a small set of known nested
 * keys via dotted names), and <b>every key it does not recognise refuses startup</b>. A permissive
 * parser that ignored unknown keys would silently swallow a typo like {@code deny_deltes = true} and
 * run with the wrong policy.
 *
 * <p>Only three scalar shapes are supported — quoted string, integer, boolean — and a bracketed list
 * of quoted strings. That is exactly what the config surface needs; anything richer is out of scope
 * for P1 and would be an unused attack surface.
 */
public final class TomlGateConfigLoader {

    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "schema_version", "project", "auth_repo", "clones_root", "target_ref_whitelist",
            "gate_home", "approvals_dir", "db_path", "blob_root", "audit_path", "locks_dir", "index_dir",
            "gate_identity.name", "gate_identity.email", "gate_identity.date",
            // 发布提交身份覆盖：留空 = 自动取本机 git 作者（[publish_identity]）。
            "publish_identity.name", "publish_identity.email",
            "policy.strictness", "policy.require_coverage", "policy.max_diff_bytes", "policy.max_diff_lines",
            "policy.engine_accept_degraded",
            "engine.cmd", "engine.args", "engine.timeout_seconds", "engine.provider_id", "engine.model",
            // 单引擎化：kind 唯一合法值 gate-engine；idle/max_tokens 为流式引擎参数；cmd/args 为 deprecated 兼容键（读取但忽略）。
            "engine.kind", "engine.idle_timeout_seconds", "engine.max_tokens",
            // 审查编排（反哺 open-code-review）：过滤 pass / 组内轮次 / 分组并发 / 单文件 token 闸门。
            "engine.review_filter", "engine.review_rounds", "engine.review_concurrency", "engine.max_file_tokens",
            "engine.resume",
            // 执行文档-后端-web §8.1: web operations console + agent session orchestration.
            "web.bind", "web.port", "web.allowed_origins", "web.human_token_file",
            "session.port_range_min", "session.port_range_max", "session.default_cli",
            "session.default_agent_config", "session.start_timeout_seconds",
            "agent.default_model", "agent.default_provider", "agent.context_template");

    public GateConfig load(Path tomlPath) {
        String text;
        try {
            text = Files.readString(tomlPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "cannot read gate.toml at " + tomlPath, e);
        }
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        parse(text, tomlPath, scalars, lists);
        assertNoUnknownKeys(scalars.keySet(), lists.keySet(), tomlPath);
        return build(scalars, lists, tomlPath);
    }

    /**
     * 文件中实际出现的键 → 原生值（Long/Boolean/String/List&lt;String&gt;），供设置中心展示"文件原始值"
     * （缺省键由调用方另行展示 default）。只复用 {@link #load} 的解析器，不经过 build() 的默认值合并 —
     * 校验语义仍归 {@link #load} 所有。
     */
    public Map<String, Object> rawValues(Path tomlPath) {
        String text;
        try {
            text = Files.readString(tomlPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "cannot read gate.toml at " + tomlPath, e);
        }
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        parse(text, tomlPath, scalars, lists);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : scalars.entrySet()) {
            out.put(e.getKey(), nativeScalar(e.getValue()));
        }
        out.putAll(lists);
        return out;
    }

    /** parseScalar 已经脱掉引号：这里只把 "true"/"false" 和整数还原成原生类型。 */
    private static Object nativeScalar(String raw) {
        if ("true".equals(raw) || "false".equals(raw)) {
            return Boolean.parseBoolean(raw);
        }
        if (raw.matches("-?\\d+")) {
            return Long.parseLong(raw);
        }
        return raw;
    }

    private void parse(String text, Path tomlPath, Map<String, String> scalars, Map<String, List<String>> lists) {
        String section = "";
        int lineNo = 0;
        for (String rawLine : text.split("\n", -1)) {
            lineNo++;
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw configError(tomlPath, lineNo, "expected key = value, got: " + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            String fullKey = section.isEmpty() ? key : section + "." + key;
            if (value.startsWith("[")) {
                lists.put(fullKey, parseList(value, tomlPath, lineNo));
            } else {
                scalars.put(fullKey, parseScalar(value, tomlPath, lineNo));
            }
        }
    }

    /** Strips a {@code #} comment that is not inside a quoted string. */
    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String parseScalar(String value, Path tomlPath, int lineNo) {
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        if (value.equals("true") || value.equals("false")) {
            return value;
        }
        if (value.matches("-?\\d+")) {
            return value;
        }
        throw configError(tomlPath, lineNo, "unsupported value (only quoted string, integer, boolean): " + value);
    }

    private static List<String> parseList(String value, Path tomlPath, int lineNo) {
        if (!value.endsWith("]")) {
            throw configError(tomlPath, lineNo, "list must be on a single line and end with ']': " + value);
        }
        String inner = value.substring(1, value.length() - 1).trim();
        List<String> out = new ArrayList<>();
        if (inner.isEmpty()) {
            return out;
        }
        for (String element : inner.split(",")) {
            String e = element.trim();
            if (!(e.startsWith("\"") && e.endsWith("\"") && e.length() >= 2)) {
                throw configError(tomlPath, lineNo, "list elements must be quoted strings: " + e);
            }
            out.add(e.substring(1, e.length() - 1));
        }
        return out;
    }

    private void assertNoUnknownKeys(java.util.Set<String> scalarKeys, java.util.Set<String> listKeys, Path tomlPath) {
        List<String> unknown = new ArrayList<>();
        for (String key : scalarKeys) {
            if (!KNOWN_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        for (String key : listKeys) {
            if (!KNOWN_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate.toml has unknown keys (fail-closed: a typo must not be silently ignored): " + unknown
                            + " in " + tomlPath);
        }
    }

    private GateConfig build(Map<String, String> scalars, Map<String, List<String>> lists, Path tomlPath) {
        int schemaVersion = intValue(scalars, "schema_version", tomlPath);
        // §8.2: refuse an outdated schema with an actionable message rather than a raw IAE. The web
        // iteration bumped CURRENT_SCHEMA_VERSION from 1 to 2 (added [web]/[session]/[agent]); config
        // is never silently migrated.
        if (schemaVersion != GateConfig.CURRENT_SCHEMA_VERSION) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "gate.toml schema_version=" + schemaVersion + " but this build expects "
                            + GateConfig.CURRENT_SCHEMA_VERSION + "; add the [web]/[session]/[agent] sections "
                            + "and bump schema_version to " + GateConfig.CURRENT_SCHEMA_VERSION
                            + " (执行文档-后端-web §8.2) in " + tomlPath);
        }
        String project = required(scalars, "project", tomlPath);
        Path gateHome = pathValue(scalars, "gate_home", tomlPath);
        Path authRepo = pathValueOr(scalars, "auth_repo", gateHome.resolveSibling("auth.git"));
        Path clonesRoot = pathValueOr(scalars, "clones_root", gateHome.resolveSibling("clones"));
        List<String> whitelist = lists.getOrDefault("target_ref_whitelist", List.of("refs/heads/main"));

        Path approvals = pathValueOr(scalars, "approvals_dir", gateHome.resolve("approvals"));
        Path db = pathValueOr(scalars, "db_path", gateHome.resolve("gate.db"));
        Path blobRoot = pathValueOr(scalars, "blob_root", gateHome.resolve("blobs"));
        Path audit = pathValueOr(scalars, "audit_path", gateHome.resolve("audit.jsonl"));
        Path locks = pathValueOr(scalars, "locks_dir", gateHome.resolve("locks"));
        Path index = pathValueOr(scalars, "index_dir", gateHome.resolve("idx"));

        CommitIdentity identity = new CommitIdentity(
                scalars.getOrDefault("gate_identity.name", "gate"),
                scalars.getOrDefault("gate_identity.email", "gate@localhost"),
                scalars.getOrDefault("gate_identity.date", "1700000000 +0000"));

        // 发布提交身份：两个键都不填 = 自动；只填一个在 record 构造时失败关闭。
        GateConfig.PublishIdentity publishIdentity = scalars.containsKey("publish_identity.name")
                || scalars.containsKey("publish_identity.email")
                ? new GateConfig.PublishIdentity(scalars.get("publish_identity.name"),
                        scalars.get("publish_identity.email"))
                : null;

        Policy.Strictness strictness = Policy.Strictness.valueOf(
                scalars.getOrDefault("policy.strictness", "BLOCKER_ONLY"));
        boolean requireCoverage = Boolean.parseBoolean(scalars.getOrDefault("policy.require_coverage", "true"));
        long maxBytes = longValueOr(scalars, "policy.max_diff_bytes", 2_000_000L);
        long maxLines = longValueOr(scalars, "policy.max_diff_lines", 20_000L);
        boolean engineAcceptDegraded = Boolean.parseBoolean(scalars.getOrDefault("policy.engine_accept_degraded", "false"));
        Policy policy = new Policy(strictness, requireCoverage, maxBytes, maxLines, engineAcceptDegraded);

        GateConfig.EngineConfig engine = null;
        if (hasAnyEngineKey(scalars, lists)) {
            engine = new GateConfig.EngineConfig(
                    scalars.get("engine.cmd"),   // deprecated：兼容旧配置读取，运行期忽略
                    lists.getOrDefault("engine.args", List.of()),
                    longValueOr(scalars, "engine.timeout_seconds", 120L),
                    scalars.get("engine.provider_id"),
                    scalars.get("engine.model"),
                    scalars.get("engine.kind"),
                    scalars.containsKey("engine.idle_timeout_seconds")
                            ? (Long) longValueOr(scalars, "engine.idle_timeout_seconds", 0L)
                            : null,
                    scalars.containsKey("engine.max_tokens")
                            ? (Long) longValueOr(scalars, "engine.max_tokens", 0L)
                            : null,
                    scalars.containsKey("engine.review_filter")
                            ? Boolean.parseBoolean(scalars.get("engine.review_filter"))
                            : null,
                    scalars.containsKey("engine.review_rounds")
                            ? (int) longValueOr(scalars, "engine.review_rounds", 0L)
                            : null,
                    scalars.containsKey("engine.review_concurrency")
                            ? (int) longValueOr(scalars, "engine.review_concurrency", 0L)
                            : null,
                    scalars.containsKey("engine.max_file_tokens")
                            ? (Long) longValueOr(scalars, "engine.max_file_tokens", 0L)
                            : null,
                    scalars.containsKey("engine.resume")
                            ? Boolean.parseBoolean(scalars.get("engine.resume"))
                            : null);
        }

        // §8.1: [web] / [session] / [agent] blocks. Absent [web] means this config never starts the
        // HTTP console (CLI/MCP paths); gate-web itself asserts web != null at startup.
        GateConfig.WebConfig web = null;
        if (scalars.containsKey("web.bind") || scalars.containsKey("web.port")
                || scalars.containsKey("web.human_token_file") || lists.containsKey("web.allowed_origins")) {
            String bind = scalars.getOrDefault("web.bind", "127.0.0.1");
            int port = (int) longValueOr(scalars, "web.port", 4097L);
            List<String> origins = lists.getOrDefault("web.allowed_origins", List.of("127.0.0.1", "localhost"));
            Path tokenFile = resolveUnderGateHome(scalars.get("web.human_token_file"),
                    gateHome, gateHome.resolve("web-token"));
            web = new GateConfig.WebConfig(bind, port, origins, tokenFile);
        }

        GateConfig.SessionConfig session = null;
        if (scalars.containsKey("session.port_range_min") || scalars.containsKey("session.port_range_max")
                || scalars.containsKey("session.default_cli") || scalars.containsKey("session.default_agent_config")
                || scalars.containsKey("session.start_timeout_seconds")) {
            session = new GateConfig.SessionConfig(
                    (int) longValueOr(scalars, "session.port_range_min", 49152L),
                    (int) longValueOr(scalars, "session.port_range_max", 65535L),
                    scalars.getOrDefault("session.default_cli", "opencode"),
                    scalars.get("session.default_agent_config"),
                    (int) longValueOr(scalars, "session.start_timeout_seconds", 60L));
        }

        GateConfig.AgentConfigDefaults agent = null;
        if (scalars.containsKey("agent.default_model") || scalars.containsKey("agent.default_provider")
                || scalars.containsKey("agent.context_template")) {
            Path template = scalars.containsKey("agent.context_template")
                    ? resolveUnderGateHome(scalars.get("agent.context_template"), gateHome, null)
                    : null;
            agent = new GateConfig.AgentConfigDefaults(
                    scalars.get("agent.default_model"),
                    scalars.get("agent.default_provider"),
                    template);
        }

        return new GateConfig(schemaVersion, project, authRepo, clonesRoot, whitelist, gateHome,
                approvals, db, blobRoot, audit, locks, index, identity, policy, engine, web, session, agent,
                publishIdentity);
    }

    /** 引擎段是否出现：旧配置以 engine.cmd 为锚，新配置以任意 engine.* 为锚（cmd 已不再是必需键）。 */
    private static boolean hasAnyEngineKey(Map<String, String> scalars, Map<String, List<String>> lists) {
        for (String k : scalars.keySet()) {
            if (k.startsWith("engine.")) {
                return true;
            }
        }
        for (String k : lists.keySet()) {
            if (k.startsWith("engine.")) {
                return true;
            }
        }
        return false;
    }

    /** Resolves a config path relative to {@code gateHome} when not absolute; empty/null uses fallback. */
    private static Path resolveUnderGateHome(String value, Path gateHome, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Path p = Path.of(value);
        return (p.isAbsolute() ? p : gateHome.resolve(p)).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> scalars, String key, Path tomlPath) {
        String v = scalars.get(key);
        if (v == null || v.isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "gate.toml missing required key: " + key + " in " + tomlPath);
        }
        return v;
    }

    private static int intValue(Map<String, String> scalars, String key, Path tomlPath) {
        return Integer.parseInt(required(scalars, key, tomlPath));
    }

    private static long longValueOr(Map<String, String> scalars, String key, long fallback) {
        String v = scalars.get(key);
        return v == null ? fallback : Long.parseLong(v);
    }

    private static Path pathValue(Map<String, String> scalars, String key, Path tomlPath) {
        return Path.of(required(scalars, key, tomlPath)).toAbsolutePath().normalize();
    }

    private static Path pathValueOr(Map<String, String> scalars, String key, Path fallback) {
        String v = scalars.get(key);
        return (v == null ? fallback : Path.of(v)).toAbsolutePath().normalize();
    }

    private static GateException configError(Path tomlPath, int lineNo, String message) {
        return new GateException(GateErrorCode.GATE_ERROR_CONFIG, tomlPath + ":" + lineNo + ": " + message);
    }
}
