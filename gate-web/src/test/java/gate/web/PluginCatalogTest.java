package gate.web;

import gate.web.plugin.PluginCatalog;
import gate.web.plugin.PluginManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 插件目录扫描 / 启停落盘 / 资产指纹（纯单元，fast 层）。 */
class PluginCatalogTest {

    @TempDir
    Path dir;

    private Path installPlugin(String id, String permissions) throws IOException {
        Path p = dir.resolve("plugins").resolve(id);
        Files.createDirectories(p.resolve("dist"));
        Files.writeString(p.resolve("manifest.json"), """
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "apiVersion": "1",
                  "entry": "dist/index.js",
                  "permissions": %s
                }
                """.formatted(id, id, permissions));
        Files.writeString(p.resolve("dist").resolve("index.js"), "export function activate(){}");
        return p;
    }

    @Test
    void missingPluginsDirYieldsEmptyList() {
        assertEquals(List.of(), new PluginCatalog(dir.resolve("plugins")).list());
    }

    @Test
    void scansValidPluginsAndSkipsBrokenOnes() throws IOException {
        installPlugin("alpha", "[]");
        installPlugin("beta", "[\"kv\"]");
        // 坏 manifest（缺 name）：跳过不炸目录
        Path broken = dir.resolve("plugins").resolve("broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("manifest.json"), "{\"id\":\"broken\",\"apiVersion\":\"1\",\"entry\":\"x.js\"}");
        // id 与目录名不一致：跳过（防止借 manifest 伪装成别的插件）
        Path masquerade = dir.resolve("plugins").resolve("gamma");
        Files.createDirectories(masquerade);
        Files.writeString(masquerade.resolve("manifest.json"), """
                {"id":"alpha","name":"X","version":"0.1","apiVersion":"1","entry":"x.js"}
                """);

        List<PluginCatalog.PluginEntry> entries = new PluginCatalog(dir.resolve("plugins")).list();
        assertEquals(2, entries.size());
        assertEquals("alpha", entries.get(0).manifest().id());
        assertEquals("beta", entries.get(1).manifest().id());
        assertTrue(entries.get(0).enabled(), "未登记状态的插件默认启用");
        assertTrue(entries.get(1).manifest().hasPermission("kv"));
    }

    @Test
    void enableDisablePersistsAcrossCatalogInstances() throws IOException {
        installPlugin("alpha", "[]");
        Path pluginsDir = dir.resolve("plugins");
        PluginCatalog c1 = new PluginCatalog(pluginsDir);
        c1.setEnabled("alpha", false);
        assertEquals(false, new PluginCatalog(pluginsDir).list().get(0).enabled());
        // 重新启用
        new PluginCatalog(pluginsDir).setEnabled("alpha", true);
        assertEquals(true, new PluginCatalog(pluginsDir).list().get(0).enabled());
    }

    @Test
    void cacheTagTracksEntryContent() throws IOException {
        installPlugin("alpha", "[]");
        PluginCatalog catalog = new PluginCatalog(dir.resolve("plugins"));
        PluginManifest m = catalog.find("alpha").orElseThrow();
        String before = catalog.cacheTag(m);
        // 产物内容变化 → 指纹变化（前端 reload 靠它做缓存击穿）
        Files.writeString(dir.resolve("plugins").resolve("alpha").resolve("dist").resolve("index.js"),
                "export function activate(){/* v2 */}");
        String after = catalog.cacheTag(m);
        assertTrue(!before.equals(after), "cache tag must change when entry changes");
    }
}
