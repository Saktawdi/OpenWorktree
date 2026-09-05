package gate.web.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed plugin manifest ({@code <gateHome>/plugins/<id>/manifest.json}).
 *
 * <p>插件包 = manifest + dist 产物。manifest 是插件与宿主之间唯一的静态契约：
 * 声明入口（entry，插件目录内相对路径）、可选样式（css）、权限（permissions）。
 * 解析即校验：id 规则、apiVersion 兼容性、entry 相对路径安全，任何违例直接抛 USAGE。
 */
public record PluginManifest(
        String id,
        String name,
        String version,
        String apiVersion,
        String entry,
        String css,
        String description,
        List<String> permissions) {

    /** 宿主当前实现的插件 API 代次；manifest 声明其他值一律拒绝加载。 */
    public static final String SUPPORTED_API_VERSION = "1";

    /** KV 能力权限：声明后宿主才下发 ctx.kv，后端 KV 端点也据此鉴权。 */
    public static final String PERMISSION_KV = "kv";

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

    /** 解析并校验 manifest 文件；文件缺失/非法 JSON/字段违例 → USAGE。 */
    public static PluginManifest parse(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new GateException(GateErrorCode.USAGE, "manifest not found: " + file);
        }
        Map<String, Object> raw;
        try {
            raw = new ObjectMapper().readValue(Files.readString(file), Json.mapper().getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (IOException e) {
            throw new GateException(GateErrorCode.USAGE, "malformed manifest JSON: " + file);
        }
        String id = text(raw.get("id"));
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new GateException(GateErrorCode.USAGE,
                    "manifest.id must match [a-z][a-z0-9-]*: " + file);
        }
        String name = requireText(raw.get("name"), "manifest.name", file);
        String version = requireText(raw.get("version"), "manifest.version", file);
        String apiVersion = text(raw.get("apiVersion"));
        if (!SUPPORTED_API_VERSION.equals(apiVersion)) {
            throw new GateException(GateErrorCode.USAGE,
                    "manifest.apiVersion must be \"" + SUPPORTED_API_VERSION + "\" (got " + apiVersion + "): " + file);
        }
        String entry = requireText(raw.get("entry"), "manifest.entry", file);
        if (!isSafeRelative(entry)) {
            throw new GateException(GateErrorCode.USAGE,
                    "manifest.entry must be a relative path inside the plugin dir: " + file);
        }
        String css = text(raw.get("css"));
        if (css != null && !isSafeRelative(css)) {
            throw new GateException(GateErrorCode.USAGE,
                    "manifest.css must be a relative path inside the plugin dir: " + file);
        }
        List<String> permissions = new ArrayList<>();
        if (raw.get("permissions") instanceof List<?> list) {
            for (Object p : list) {
                String s = p == null ? null : p.toString();
                if (s == null || s.isBlank() || s.length() > 32 || !s.matches("[a-z0-9-]+")) {
                    throw new GateException(GateErrorCode.USAGE, "manifest.permissions entries must be [a-z0-9-]+: " + file);
                }
                permissions.add(s);
            }
        } else if (raw.get("permissions") != null) {
            throw new GateException(GateErrorCode.USAGE, "manifest.permissions must be an array: " + file);
        }
        return new PluginManifest(id, name, version, apiVersion, entry, css,
                text(raw.get("description")), List.copyOf(permissions));
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static String requireText(Object v, String what, Path file) {
        String s = text(v);
        if (s == null) {
            throw new GateException(GateErrorCode.USAGE, what + " is required: " + file);
        }
        return s;
    }

    /** 相对路径且不越界：拒绝绝对路径、反斜杠与 ".." 片段。 */
    private static boolean isSafeRelative(String p) {
        if (p.startsWith("/") || p.contains("\\") || p.contains("..")) {
            return false;
        }
        return !p.isBlank();
    }
}
