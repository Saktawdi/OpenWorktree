package gate.web.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件目录扫描 + 启用状态持久化 + 资产指纹（cache tag）。
 *
 * <p>目录布局（扫描式，放入即出现，删除即消失——热插拔的本地优先形态）：
 * <pre>
 *   &lt;gateHome&gt;/plugins/
 *     plugins-state.json      {"enabled": {"&lt;id&gt;": false}}   未登记 = 默认启用
 *     &lt;pluginId&gt;/manifest.json
 *     &lt;pluginId&gt;/dist/index.js …
 * </pre>
 *
 * <p>cacheTag = sha256(manifest.json + entry 产物)。前端以 {@code ?v=cacheTag} 做 ES module
 * 的缓存击穿：reload 时指纹变化即拿到新代码，指纹不变则继续用浏览器缓存。
 */
public final class PluginCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(PluginCatalog.class);

    private static final String STATE_FILE = "plugins-state.json";

    private final Path pluginsDir;
    private final ObjectMapper mapper = Json.mapper();

    public PluginCatalog(Path pluginsDir) {
        this.pluginsDir = pluginsDir.toAbsolutePath().normalize();
    }

    /** 目录下每个含合法 manifest 的子目录一条；非法 manifest 记 warn 后跳过（坏插件不拖垮目录）。 */
    public List<PluginEntry> list() {
        List<PluginEntry> out = new ArrayList<>();
        if (!Files.isDirectory(pluginsDir)) {
            return out;
        }
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> {
                        try {
                            PluginManifest manifest = PluginManifest.parse(dir.resolve("manifest.json"));
                            if (!dir.getFileName().toString().equals(manifest.id())) {
                                throw new GateException(GateErrorCode.USAGE,
                                        "manifest.id must equal the directory name: " + dir);
                            }
                            out.add(new PluginEntry(manifest, isEnabled(manifest.id()), cacheTag(manifest)));
                        } catch (RuntimeException e) {
                            LOG.warn("skipping invalid plugin dir {}: {}", dir, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to scan plugins dir: " + pluginsDir);
        }
        return out;
    }

    /** 启用/禁用并落盘（写回 state 文件）。未知插件 id → USAGE。 */
    public void setEnabled(String id, boolean enabled) {
        require(id);
        Map<String, Object> state = readState();
        @SuppressWarnings("unchecked")
        Map<String, Object> enabledMap = (Map<String, Object>) state.computeIfAbsent("enabled", k -> new LinkedHashMap<>());
        enabledMap.put(id, enabled);
        writeState(state);
    }

    public Optional<PluginManifest> find(String id) {
        if (!isId(id)) {
            return Optional.empty();
        }
        try {
            return Optional.of(PluginManifest.parse(pluginsDir.resolve(id).resolve("manifest.json")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public Path pluginDir(String id) {
        if (!isId(id)) {
            throw new GateException(GateErrorCode.USAGE, "invalid plugin id: " + id);
        }
        return pluginsDir.resolve(id);
    }

    /** state 未登记的插件默认启用（放入即生效，与目录扫描语义一致）。 */
    public boolean isEnabled(String id) {
        Object raw = readState().get("enabled");
        if (raw instanceof Map<?, ?> m) {
            return !Boolean.FALSE.equals(m.get(id));
        }
        return true;
    }

    /** manifest + entry 产物的 sha256 前 12 位十六进制；entry 缺失时仅指纹 manifest。 */
    public String cacheTag(PluginManifest manifest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(pluginsDir.resolve(manifest.id()).resolve("manifest.json")));
            Path entry = pluginsDir.resolve(manifest.id()).resolve(manifest.entry()).normalize();
            if (Files.isRegularFile(entry)) {
                digest.update(Files.readAllBytes(entry));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (IOException | NoSuchAlgorithmException e) {
            // 指纹只是缓存击穿手段而非安全边界：退化用 mtime 也能保证"变了就换值"。
            return Long.toHexString(System.currentTimeMillis());
        }
    }

    private void require(String id) {
        if (find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "unknown plugin: " + id);
        }
    }

    private static boolean isId(String id) {
        return id != null && id.matches("[a-z][a-z0-9-]*");
    }

    private Map<String, Object> readState() {
        Path file = pluginsDir.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(Files.readString(file),
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return parsed;
        } catch (IOException e) {
            LOG.warn("corrupt plugins-state.json, rebuilding: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeState(Map<String, Object> state) {
        try {
            Files.createDirectories(pluginsDir);
            Path file = pluginsDir.resolve(STATE_FILE);
            Path tmp = pluginsDir.resolve(STATE_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to write plugins-state.json");
        }
    }

    /** 目录里扫出的一条插件（manifest + 当前启用状态 + 资产指纹）。 */
    public record PluginEntry(PluginManifest manifest, boolean enabled, String cacheTag) {
    }
}
