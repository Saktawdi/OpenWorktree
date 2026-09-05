package gate.web;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.plugin.PluginManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 插件 manifest 解析与校验（纯单元，fast 层）。 */
class PluginManifestTest {

    @TempDir
    Path dir;

    private PluginManifest parse(String json) throws IOException {
        Path file = dir.resolve("manifest.json");
        Files.writeString(file, json);
        return PluginManifest.parse(file);
    }

    @Test
    void parsesValidManifestWithAllFields() throws IOException {
        PluginManifest m = parse("""
                {
                  "id": "quick-quotes",
                  "name": "快捷语录",
                  "version": "0.1.0",
                  "apiVersion": "1",
                  "entry": "dist/index.js",
                  "css": "dist/style.css",
                  "description": "语录像素",
                  "permissions": ["kv"]
                }
                """);
        assertEquals("quick-quotes", m.id());
        assertEquals("快捷语录", m.name());
        assertEquals("dist/index.js", m.entry());
        assertEquals("dist/style.css", m.css());
        assertEquals(1, m.permissions().size());
        assertTrue(m.hasPermission(PluginManifest.PERMISSION_KV));
    }

    @Test
    void optionalFieldsDefaultToEmpty() throws IOException {
        PluginManifest m = parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"index.js"}
                """);
        assertEquals(null, m.css());
        assertEquals(null, m.description());
        assertEquals(0, m.permissions().size());
        assertTrue(!m.hasPermission("kv"));
    }

    @Test
    void rejectsUnsupportedApiVersion() throws IOException {
        GateException e = assertThrows(GateException.class,
                () -> parse("""
                        {"id":"a","name":"A","version":"0.1","apiVersion":"2","entry":"index.js"}
                        """));
        assertEquals(GateErrorCode.USAGE, e.code());
    }

    @Test
    void rejectsInvalidPluginId() throws IOException {
        assertThrows(GateException.class,
                () -> parse("""
                        {"id":"Bad_Id","name":"A","version":"0.1","apiVersion":"1","entry":"index.js"}
                        """));
        assertThrows(GateException.class,
                () -> parse("""
                        {"id":"","name":"A","version":"0.1","apiVersion":"1","entry":"index.js"}
                        """));
    }

    @Test
    void rejectsMissingRequiredFields() throws IOException {
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","version":"0.1","apiVersion":"1","entry":"index.js"}
                """));
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","apiVersion":"1","entry":"index.js"}
                """));
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1"}
                """));
    }

    @Test
    void rejectsUnsafeOrAbsoluteEntryPath() throws IOException {
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"../escape.js"}
                """));
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"/abs/index.js"}
                """));
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"ok.js","css":"../evil.css"}
                """));
    }

    @Test
    void rejectsMalformedPermissions() throws IOException {
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"i.js","permissions":"kv"}
                """));
        assertThrows(GateException.class, () -> parse("""
                {"id":"a","name":"A","version":"0.1","apiVersion":"1","entry":"i.js","permissions":["Not Ok"]}
                """));
    }

    @Test
    void rejectsMissingOrMalformedFile() throws IOException {
        assertThrows(GateException.class, () -> PluginManifest.parse(dir.resolve("nope.json")));
        Files.writeString(dir.resolve("broken.json"), "{not json");
        assertThrows(GateException.class, () -> PluginManifest.parse(dir.resolve("broken.json")));
    }
}
