package gate.web.plugin;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * 插件数据（KV）持久化：每个插件一个命名空间目录，一个 key 一个 JSON 文件。
 *
 * <pre>
 *   &lt;gateHome&gt;/plugins-data/&lt;pluginId&gt;/&lt;key&gt;.json   （key 限 [A-Za-z0-9._-]{1,64}）
 * </pre>
 *
 * <p>写入走 temp + atomic move：读写并发（管理页编辑防抖保存）不会留下半截文件。
 * 值本身是任意合法 JSON（上限 {@link #MAX_VALUE_BYTES}），由宿主/插件自行解释。
 */
public final class PluginDataStore {

    /** 单值上限：语录清单等管理数据远小于此；防误写把磁盘塞爆。 */
    public static final int MAX_VALUE_BYTES = 256 * 1024;

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path root;

    public PluginDataStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 读原始 JSON 文本；key 不存在返回 null。插件 id 与 key 非法 → USAGE。 */
    public String read(String pluginId, String key) {
        Path file = fileFor(pluginId, key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to read plugin data: " + file);
        }
    }

    /** 写原始 JSON 文本（调用方负责保证 body 是合法 JSON）。 */
    public void write(String pluginId, String key, String rawJson) {
        Path file = fileFor(pluginId, key);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, rawJson == null ? "null" : rawJson, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to write plugin data: " + file);
        }
    }

    /** 删除 key；返回是否真的删除了。 */
    public boolean delete(String pluginId, String key) {
        try {
            return Files.deleteIfExists(fileFor(pluginId, key));
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "failed to delete plugin data");
        }
    }

    private Path fileFor(String pluginId, String key) {
        if (pluginId == null || !pluginId.matches("[a-z][a-z0-9-]*")) {
            throw new GateException(GateErrorCode.USAGE, "invalid plugin id: " + pluginId);
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new GateException(GateErrorCode.USAGE, "invalid kv key: " + key);
        }
        return root.resolve(pluginId).resolve(key + ".json");
    }
}
