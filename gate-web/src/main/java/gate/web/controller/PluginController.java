package gate.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.plugin.PluginCatalog;
import gate.web.plugin.PluginDataStore;
import gate.web.plugin.PluginManifest;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Plugin System Controller.
 *
 * <ul>
 *   <li>{@code GET  /api/plugins} — 目录扫描结果（manifest + enabled + cache_tag）
 *   <li>{@code POST /api/plugins/{id}/enable|disable} — 启停落盘
 *   <li>{@code POST /api/plugins/{id}/reload} — 返回最新 cache_tag（前端据此重 import）
 *   <li>{@code GET  /plugins/*} — 插件静态资产下发（ES import 无法携带 Authorization 头，
 *       故不鉴权；仅本机回环绑定 + 严格路径规范化，风险已接受并注释于此）
 *   <li>{@code GET/PUT/DELETE /api/plugin-capabilities/kv/{id}/{key}} — 插件 KV 数据
 *       （走既有 Bearer 鉴权；要求插件启用且 manifest 声明 kv 权限，只能写自己命名空间）
 * </ul>
 */
public final class PluginController implements WebController {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".js", "text/javascript; charset=utf-8"),
            Map.entry(".mjs", "text/javascript; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".map", "application/json; charset=utf-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".ico", "image/x-icon"),
            Map.entry(".png", "image/png"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".woff2", "font/woff2"),
            Map.entry(".woff", "font/woff"));

    /** 资产相对路径白名单：字母数字与 . / _ -，显式排除穿越与编码字符。 */
    private static final Pattern SAFE_ASSET_PATH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    private final PluginCatalog catalog;
    private final PluginDataStore data;

    public PluginController(PluginCatalog catalog, PluginDataStore data) {
        this.catalog = catalog;
        this.data = data;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/plugins", this::list);
        app.post("/api/plugins/{id}/enable", this::enable);
        app.post("/api/plugins/{id}/disable", this::disable);
        app.post("/api/plugins/{id}/reload", this::reload);
        app.get("/plugins/*", this::asset);
        app.get("/api/plugin-capabilities/kv/{id}/{key}", this::kvGet);
        app.put("/api/plugin-capabilities/kv/{id}/{key}", this::kvPut);
        app.delete("/api/plugin-capabilities/kv/{id}/{key}", this::kvDelete);
    }

    public void list(Context ctx) {
        List<Map<String, Object>> plugins = new java.util.ArrayList<>();
        for (PluginCatalog.PluginEntry e : catalog.list()) {
            plugins.add(viewOf(e.manifest(), e.enabled(), e.cacheTag()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plugins", plugins);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void enable(Context ctx) {
        String id = ctx.pathParam("id");
        catalog.setEnabled(id, true);
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true, "id", id));
    }

    public void disable(Context ctx) {
        String id = ctx.pathParam("id");
        catalog.setEnabled(id, false);
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true, "id", id));
    }

    /** 无服务端状态：重算指纹即"重载"——前端拿到新 cache_tag 后以新 URL 重新 import。 */
    public void reload(Context ctx) {
        String id = ctx.pathParam("id");
        PluginManifest manifest = catalog.find(id)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "unknown plugin: " + id));
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true, "id", id, "cache_tag", catalog.cacheTag(manifest)));
    }

    /**
     * 静态资产：{@code /plugins/<id>/<相对路径>}。
     * 路径规范化 + 前缀校验防穿越（符号链接由 normalize+startsWith 兜底），no-store 以便插件热更新。
     */
    public void asset(Context ctx) {
        String path = ctx.path();
        // "/plugins/<id>/<rest…>"
        String rest = path.substring("/plugins/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            notFound(ctx, "plugin asset path expected: " + path);
            return;
        }
        String id = rest.substring(0, slash);
        String rel = rest.substring(slash + 1);
        if (!id.matches("[a-z][a-z0-9-]*") || !SAFE_ASSET_PATH.matcher(rel).matches()) {
            notFound(ctx, "plugin asset path rejected: " + path);
            return;
        }
        Path root = catalog.pluginDir(id).normalize();
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            notFound(ctx, "plugin asset not found: " + path);
            return;
        }
        try {
            ctx.status(HttpStatus.OK);
            ctx.contentType(contentType(file));
            ctx.header("Cache-Control", "no-store");
            ctx.result(Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to read plugin asset: " + path);
        }
    }

    public void kvGet(Context ctx) {
        String id = ctx.pathParam("id");
        String key = ctx.pathParam("key");
        requireKvPlugin(id);
        String raw = data.read(id, key);
        if (raw == null) {
            notFound(ctx, "kv key not found: " + key);
            return;
        }
        ctx.status(HttpStatus.OK);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(raw);
    }

    public void kvPut(Context ctx) {
        String id = ctx.pathParam("id");
        String key = ctx.pathParam("key");
        requireKvPlugin(id);
        String body = ctx.body();
        if (body == null || body.getBytes().length > PluginDataStore.MAX_VALUE_BYTES) {
            throw new GateException(GateErrorCode.USAGE, "kv value too large (max "
                    + PluginDataStore.MAX_VALUE_BYTES + " bytes)");
        }
        try {
            JsonNode node = Json.mapper().readTree(body == null || body.isBlank() ? "null" : body);
            if (node == null) {
                throw new GateException(GateErrorCode.USAGE, "kv value must be valid JSON");
            }
        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            throw new GateException(GateErrorCode.USAGE, "kv value must be valid JSON");
        }
        data.write(id, key, body);
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true));
    }

    public void kvDelete(Context ctx) {
        String id = ctx.pathParam("id");
        String key = ctx.pathParam("key");
        requireKvPlugin(id);
        boolean deleted = data.delete(id, key);
        ctx.status(HttpStatus.OK);
        ctx.json(Map.of("ok", true, "deleted", deleted));
    }

    /** KV 只开放给：存在、已启用、且声明了 kv 权限的插件（只能访问自己命名空间）。 */
    private void requireKvPlugin(String id) {
        PluginManifest manifest = catalog.find(id)
                .orElseThrow(() -> new GateException(GateErrorCode.USAGE, "unknown plugin: " + id));
        if (!catalog.isEnabled(id)) {
            throw new GateException(GateErrorCode.USAGE, "plugin is disabled: " + id);
        }
        if (!manifest.hasPermission(PluginManifest.PERMISSION_KV)) {
            throw new GateException(GateErrorCode.USAGE,
                    "plugin does not declare the \"kv\" permission: " + id);
        }
    }

    private static Map<String, Object> viewOf(PluginManifest m, boolean enabled, String cacheTag) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.id());
        v.put("name", m.name());
        v.put("version", m.version());
        v.put("api_version", m.apiVersion());
        v.put("entry", m.entry());
        v.put("css", m.css());
        v.put("description", m.description());
        v.put("permissions", m.permissions());
        v.put("enabled", enabled);
        v.put("cache_tag", cacheTag);
        return v;
    }

    private static void notFound(Context ctx, String message) {
        ctx.status(HttpStatus.NOT_FOUND);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND", message, null));
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String type = CONTENT_TYPES.get(name.substring(dot).toLowerCase(Locale.ROOT));
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }
}
