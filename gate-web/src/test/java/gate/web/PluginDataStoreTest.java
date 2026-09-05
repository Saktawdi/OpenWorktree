package gate.web;

import gate.web.plugin.PluginDataStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 插件 KV 数据持久化（纯单元，fast 层）。 */
class PluginDataStoreTest {

    @TempDir
    Path dir;

    @Test
    void writeReadDeleteRoundtrip() throws IOException {
        PluginDataStore store = new PluginDataStore(dir.resolve("data"));
        store.write("alpha", "quotes", "[{\"id\":1}]");
        assertEquals("[{\"id\":1}]", store.read("alpha", "quotes"));
        assertTrue(store.delete("alpha", "quotes"));
        assertNull(store.read("alpha", "quotes"));
        assertFalse(store.delete("alpha", "quotes"), "second delete is a no-op");
    }

    @Test
    void namespacesArePerPluginDirectories() throws IOException {
        PluginDataStore store = new PluginDataStore(dir.resolve("data"));
        store.write("alpha", "key", "\"a\"");
        store.write("beta", "key", "\"b\"");
        assertEquals("\"a\"", store.read("alpha", "key"));
        assertEquals("\"b\"", store.read("beta", "key"));
        assertEquals(2, Files.list(dir.resolve("data")).count());
    }

    @Test
    void rejectsInvalidPluginIdOrKey() {
        PluginDataStore store = new PluginDataStore(dir.resolve("data"));
        assertThrows(Exception.class, () -> store.write("../escape", "k", "1"));
        assertThrows(Exception.class, () -> store.write("alpha", "../escape", "1"));
        assertThrows(Exception.class, () -> store.write("alpha", "a/b", "1"));
        assertThrows(Exception.class, () -> store.write("alpha", "", "1"));
        // key 白名单允许点/横线/下划线
        store.write("alpha", "quotes.v2_backup", "1");
        assertEquals("1", store.read("alpha", "quotes.v2_backup"));
    }

    @Test
    void atomicWriteLeavesNoTempResidue() throws IOException {
        PluginDataStore store = new PluginDataStore(dir.resolve("data"));
        store.write("alpha", "k", "\"1\"");
        store.write("alpha", "k", "\"2\"");
        assertEquals("\"2\"", store.read("alpha", "k"));
        assertEquals(1, Files.list(dir.resolve("data").resolve("alpha")).count(),
                ".tmp residue must be moved away");
    }
}
