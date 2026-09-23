package gate.web.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/write access to the local OpenCode CLI configuration file
 * ({@code ~/.config/opencode/opencode.jsonc} / {@code opencode.json}, overridable via the
 * {@code OPENCODE_CONFIG} environment variable or the {@code opencode.config.path} system property
 * (the property wins, so the console tests can point at a temp file without touching the user's
 * real config).
 *
 * <p>Scope is deliberately narrow: only the top-level {@code provider} object is mutated — every
 * other key ({@code model}, {@code plugin}, {@code mcp}, …) round-trips untouched. Reading is
 * JSONC-tolerant (comments / trailing commas), writing always emits strict pretty JSON, and every
 * write first copies the current file to {@code <file>.bak} so a bad save is one rename away from
 * recovery.
 */
public final class OpenCodeConfigService {

    /** System property override resolved on every access so tests never order-depend on startup. */
    public static final String CONFIG_PATH_PROPERTY = "opencode.config.path";

    private static final ObjectMapper LENIENT = new ObjectMapper(JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build());

    private static final ObjectMapper STRICT = new ObjectMapper();

    /** Resolved config file location plus whether the file currently exists. */
    public record ConfigLocation(Path path, boolean exists) {
    }

    public OpenCodeConfigService() {
    }

    /** Locates the config file: system property → OPENCODE_CONFIG env → default dir. */
    public ConfigLocation locate() {
        String override = System.getProperty(CONFIG_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim());
            return new ConfigLocation(p, Files.exists(p));
        }
        String env = System.getenv("OPENCODE_CONFIG");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim());
            return new ConfigLocation(p, Files.exists(p));
        }
        Path dir = Path.of(System.getProperty("user.home"), ".config", "opencode");
        Path jsonc = dir.resolve("opencode.jsonc");
        if (Files.exists(jsonc)) {
            return new ConfigLocation(jsonc, true);
        }
        Path json = dir.resolve("opencode.json");
        return new ConfigLocation(json, Files.exists(json));
    }

    /**
     * The whole {@code provider} object as stored in the file (insertion-ordered). Missing file or
     * missing node → an empty map, never an error, so the list page renders for a fresh opencode
     * install too.
     */
    public Map<String, Object> readProviders() {
        ConfigLocation loc = locate();
        if (!loc.exists()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> root = readRoot(loc.path());
        return providersNode(root);
    }

    /** Inserts or replaces one provider entry and persists the file; returns the updated node. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upsertProvider(String key, Map<String, Object> provider) {
        if (key == null || key.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "provider key is required");
        }
        if (!key.matches("[A-Za-z0-9._\\-/]+")) {
            throw new GateException(GateErrorCode.USAGE,
                    "provider key may only contain letters, digits, '.', '_', '-' and '/'");
        }
        ConfigLocation loc = locate();
        Map<String, Object> root = loc.exists() ? readRoot(loc.path()) : new LinkedHashMap<>();
        Object existingNode = root.get("provider");
        Map<String, Object> providers = existingNode instanceof Map<?, ?> m
                ? asLinked(m)
                : new LinkedHashMap<>();
        Object previous = providers.get(key);
        Map<String, Object> merged = previous instanceof Map<?, ?> prev
                ? asLinked(prev)
                : new LinkedHashMap<>();
        mergeProvider(merged, provider);
        validateModels(key, merged);
        providers.put(key, merged);
        root.put("provider", providers);
        writeRoot(loc, root);
        return providers;
    }

    /** Removes one provider entry and persists the file; fails when the key is unknown. */
    @SuppressWarnings("unchecked")
    public void deleteProvider(String key) {
        ConfigLocation loc = locate();
        Map<String, Object> root = loc.exists() ? readRoot(loc.path()) : new LinkedHashMap<>();
        Object existingNode = root.get("provider");
        Map<String, Object> providers = existingNode instanceof Map<?, ?> m
                ? asLinked(m)
                : new LinkedHashMap<>();
        if (providers.remove(key) == null) {
            throw new GateException(GateErrorCode.USAGE, "no such opencode provider: " + key);
        }
        root.put("provider", providers);
        writeRoot(loc, root);
    }

    /** Extracts the flat view (name/npm/baseURL/apiKey/model ids) one provider row exposes. */
    public static Map<String, Object> renderProvider(String key, Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("name", strOr(raw.get("name"), key));
        row.put("npm", raw.get("npm"));
        Object options = raw.get("options");
        Map<String, Object> opts = options instanceof Map<?, ?> m ? asLinked(m) : new LinkedHashMap<>();
        row.put("base_url", opts.get("baseURL"));
        row.put("api_key", opts.get("apiKey"));
        Object models = raw.get("models");
        List<Map<String, Object>> modelEntries = new ArrayList<>();
        if (models instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", String.valueOf(e.getKey()));
                entry.put("config", e.getValue() instanceof Map<?, ?> cm
                        ? asLinked(cm)
                        : new LinkedHashMap<String, Object>());
                modelEntries.add(entry);
            }
        }
        row.put("models", modelEntries);
        row.put("model_count", modelEntries.size());
        return row;
    }

    /**
     * Field-wise merge of the request onto the stored entry: null/absent request fields keep the
     * stored value (so an edit of just the API key cannot drop a hand-tuned {@code models} block),
     * and a blank api_key clears the stored key.
     */
    private static void mergeProvider(Map<String, Object> target, Map<String, Object> req) {
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) {
                target.remove("name");
            } else {
                target.put("name", name.trim());
            }
        }
        if (req.containsKey("npm")) {
            String npm = str(req.get("npm"));
            if (npm == null || npm.isBlank()) {
                target.remove("npm");
            } else {
                target.put("npm", npm.trim());
            }
        }
        Map<String, Object> opts = target.get("options") instanceof Map<?, ?> m
                ? asLinked(m)
                : new LinkedHashMap<>();
        if (req.containsKey("base_url")) {
            String baseUrl = str(req.get("base_url"));
            if (baseUrl == null || baseUrl.isBlank()) {
                opts.remove("baseURL");
            } else {
                opts.put("baseURL", baseUrl.trim());
            }
        }
        if (req.containsKey("api_key")) {
            String apiKey = str(req.get("api_key"));
            if (apiKey == null || apiKey.isBlank()) {
                opts.remove("apiKey");
            } else {
                opts.put("apiKey", apiKey.trim());
            }
        }
        if (!opts.isEmpty()) {
            target.put("options", opts);
        } else {
            target.remove("options");
        }
        if (req.containsKey("models")) {
            target.remove("models");
            Object rawModels = req.get("models");
            Map<String, Object> models = new LinkedHashMap<>();
            if (rawModels instanceof Map<?, ?> mm) {
                // 新形状：id → 完整模型配置（limit/modalities/variants/… 原样透传）。
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    String id = str(e.getKey());
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    models.put(id.trim(), e.getValue() instanceof Map<?, ?> cm
                            ? new LinkedHashMap<>(asLinked(cm))
                            : new LinkedHashMap<String, Object>());
                }
            } else if (rawModels instanceof List<?> list) {
                // 兼容旧形状：仅模型 id 列表，配置留空由 opencode 默认。
                for (Object id : list) {
                    String modelId = str(id);
                    if (modelId != null && !modelId.isBlank() && !models.containsKey(modelId.trim())) {
                        models.put(modelId.trim(), new LinkedHashMap<String, Object>());
                    }
                }
            }
            if (!models.isEmpty()) {
                target.put("models", models);
            }
        }
    }

    /**
     * 落盘前的模型节点体检：opencode 的 schema 要求 {@code limit} 一旦出现，{@code context}
     * 与 {@code output} 就必须同时是正数。缺一个的代价不是「这个模型不好使」，而是 opencode
     * 启动时拒绝整份配置——serve 直接退出，本机所有会话一起失效，而错误文案只指向端口，排查
     * 方向被带偏（真实案例：模型编辑器只填上下文，值 1000000 写了出去，全部会话报
     * {@code serve exited before becoming healthy (exit 1)}）。坏配置宁可不写。
     *
     * <p>只体检本次写入的 provider 节点，不做全文件校验：既有坏节点（手改的、旧版本写的）必须
     * 还能被改写或删除救回来，否则用户只能自己去手改文件。
     */
    private static void validateModels(String key, Map<String, Object> provider) {
        if (!(provider.get("models") instanceof Map<?, ?> models)) {
            return;
        }
        for (Map.Entry<?, ?> e : models.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> model) {
                validateLimit(key, String.valueOf(e.getKey()), model.get("limit"));
            }
        }
    }

    private static void validateLimit(String key, String modelId, Object limit) {
        if (limit == null) {
            return; // 不写 limit 合法：opencode 用它自己那份默认
        }
        if (!(limit instanceof Map<?, ?> lim)) {
            throw limitRejected(key, modelId, "limit must be an object");
        }
        requirePositiveLimit(key, modelId, lim, "context");
        requirePositiveLimit(key, modelId, lim, "output");
    }

    private static void requirePositiveLimit(String key, String modelId, Map<?, ?> limit, String field) {
        Object raw = limit.get(field);
        if (raw == null) {
            throw limitRejected(key, modelId, "limit." + field + " is required whenever limit is set"
                    + " (opencode rejects the whole config, so no session can start)");
        }
        if (!(raw instanceof Number n) || n.doubleValue() <= 0
                || n.doubleValue() != Math.floor(n.doubleValue())) {
            throw limitRejected(key, modelId, "limit." + field + " must be a positive integer, got " + raw);
        }
    }

    private static GateException limitRejected(String key, String modelId, String why) {
        return new GateException(GateErrorCode.USAGE, "provider " + key + " model " + modelId + ": " + why);
    }

    private static Map<String, Object> readRoot(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return new LinkedHashMap<>();
            }
            Object parsed = LENIENT.readValue(content, Object.class);
            return parsed instanceof Map<?, ?> m ? asLinked(m) : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "failed to read opencode config " + path + ": " + e.getMessage(), e);
        }
    }

    private void writeRoot(ConfigLocation loc, Map<String, Object> root) {
        try {
            Path path = loc.path();
            if (loc.exists()) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createDirectories(path.getParent());
            }
            String json = STRICT.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "failed to write opencode config " + loc.path() + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> providersNode(Map<String, Object> root) {
        Object node = root.get("provider");
        return node instanceof Map<?, ?> m ? asLinked(m) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asLinked(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String strOr(Object value, String fallback) {
        String s = str(value);
        return s == null || s.isBlank() ? fallback : s;
    }
}
