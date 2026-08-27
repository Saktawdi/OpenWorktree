package gate.adapters.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.config.GateConfig;
import gate.domain.error.GateException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 设置中心 gate.toml 写回（V5 web console）：行级替换保留注释/分区/换行风格，候选文本先经
 * {@link TomlGateConfigLoader} 完整校验，失败时原文件与备份都不得被破坏（fail-closed）。
 */
class TomlGateConfigWriterTest {

    @TempDir
    Path dir;

    private Path toml;
    private final TomlGateConfigWriter writer = new TomlGateConfigWriter();

    @BeforeEach
    void setUp() throws IOException {
        toml = dir.resolve("gate.toml");
        Files.writeString(toml, sampleToml("\n"), StandardCharsets.UTF_8);
    }

    private static String sampleToml(String eol) {
        return "# 本地验收运行时（注释必须保留）" + eol
                + "schema_version = 2" + eol
                + "project = \"local-run\"" + eol
                + "gate_home = \"gate-home\"" + eol
                + "target_ref_whitelist = [\"refs/heads/main\"]" + eol
                + eol
                + "[gate_identity]" + eol
                + "name = \"gate\"" + eol
                + "email = \"gate@localhost\"" + eol
                + eol
                + "[web]" + eol
                + "bind = \"127.0.0.1\"" + eol
                + "port = 4097" + eol
                + "allowed_origins = [\"127.0.0.1\", \"localhost\"]" + eol
                + eol
                + "# 会话编排默认值" + eol
                + "[session]" + eol
                + "port_range_min = 49152" + eol
                + "port_range_max = 61000" + eol
                + "default_cli = \"claude\"" + eol
                + "start_timeout_seconds = 60" + eol;
    }

    @Test
    void updatePreservesCommentsAndSectionOrder() throws IOException {
        writer.write(toml, Map.of(
                "project", "demo-2",
                "session.start_timeout_seconds", 90));

        String text = Files.readString(toml, StandardCharsets.UTF_8);
        assertTrue(text.contains("# 本地验收运行时（注释必须保留）"), text);
        assertTrue(text.contains("# 会话编排默认值"), text);
        assertTrue(text.indexOf("[web]") < text.indexOf("[session]"), text);
        assertTrue(text.contains("project = \"demo-2\""), text);
        assertTrue(text.contains("start_timeout_seconds = 90"), text);

        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertEquals("demo-2", reloaded.project());
        assertEquals(90, reloaded.session().startTimeoutSeconds());
    }

    @Test
    void nullValueRemovesKeyAndFallsBackToDefault() throws IOException {
        Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("session.default_cli", null);
        writer.write(toml, updates);

        String text = Files.readString(toml, StandardCharsets.UTF_8);
        assertFalse(text.contains("default_cli"), text);
        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertEquals("claude", reloaded.session().defaultCli());
    }

    @Test
    void missingSectionIsAppendedAtFileEnd() throws IOException {
        writer.write(toml, Map.of("agent.default_model", "gpt-x"));

        String text = Files.readString(toml, StandardCharsets.UTF_8);
        assertTrue(text.contains("[agent]"), text);
        assertTrue(text.contains("default_model = \"gpt-x\""), text);
        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertEquals("gpt-x", reloaded.agent().defaultModel());
    }

    @Test
    void listValueIsWrittenOnASingleLine() throws IOException {
        writer.write(toml, Map.of("web.allowed_origins", List.of("127.0.0.1", "localhost", "gate.local")));

        String text = Files.readString(toml, StandardCharsets.UTF_8);
        assertTrue(text.contains("allowed_origins = [\"127.0.0.1\", \"localhost\", \"gate.local\"]"), text);
        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertEquals(List.of("127.0.0.1", "localhost", "gate.local"), reloaded.web().allowedOrigins());
    }

    @Test
    void unknownKeyRejectedAndOriginalUntouched() throws IOException {
        byte[] before = Files.readAllBytes(toml);
        GateException e = assertThrows(GateException.class,
                () -> writer.write(toml, Map.of("typo_key", 1)));
        assertTrue(e.getMessage().contains("unknown key"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(toml));
    }

    @Test
    void nonEditableKeyRejected() {
        GateException e = assertThrows(GateException.class,
                () -> writer.write(toml, Map.of("db_path", "elsewhere/gate.db")));
        assertTrue(e.getMessage().contains("not editable"), e.getMessage());
    }

    @Test
    void typeMismatchRejected() {
        GateException e = assertThrows(GateException.class,
                () -> writer.write(toml, Map.of("web.port", "4097"))); // string for an int key
        assertTrue(e.getMessage().contains("type mismatch"), e.getMessage());
    }

    @Test
    void invalidValueFailsValidationWithOriginalAndBackupUntouched() throws IOException {
        byte[] before = Files.readAllBytes(toml);
        GateException e = assertThrows(GateException.class,
                () -> writer.write(toml, Map.of("web.bind", "0.0.0.0")));
        // WebConfig 的回环校验在候选校验阶段拒绝：原文件未动，也不产生备份。
        assertTrue(e.getMessage().contains("0.0.0.0"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(toml));
        assertFalse(Files.exists(toml.resolveSibling("gate.toml.bak")));
    }

    @Test
    void engineKeysEditableWithoutLegacyCmd() throws IOException {
        // 单引擎化后：engine.* 不再要求携带 engine.cmd；缺省 kind 归一为 gate-engine、idle 缺省 90s。
        writer.write(toml, Map.of("engine.provider_id", "temp", "engine.model", "deepseek-v4-flash-0731"));

        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertTrue(reloaded.engineConfigured());
        assertEquals("gate-engine", reloaded.engine().kind());
        assertEquals("temp", reloaded.engine().providerId());
        assertEquals("deepseek-v4-flash-0731", reloaded.engine().model());
        assertEquals(90L, reloaded.engine().idleTimeoutSeconds(), "idle 未配置时归一为默认 90s");
        assertNull(reloaded.engine().maxTokens());
    }

    @Test
    void fullEngineRoundTripKeepsAllKeys() throws IOException {
        writer.write(toml, Map.of(
                "engine.kind", "gate-engine",
                "engine.provider_id", "temp",
                "engine.model", "test-model",
                "engine.timeout_seconds", 600,
                "engine.idle_timeout_seconds", 120,
                "engine.max_tokens", 8192));

        GateConfig reloaded = new TomlGateConfigLoader().load(toml);
        assertEquals("gate-engine", reloaded.engine().kind());
        assertEquals("temp", reloaded.engine().providerId());
        assertEquals("test-model", reloaded.engine().model());
        assertEquals(600, reloaded.engine().timeoutSeconds());
        assertEquals(120L, reloaded.engine().idleTimeoutSeconds());
        assertEquals(8192L, reloaded.engine().maxTokens());
    }

    @Test
    void legacyUnknownKindRejectedAtCandidateValidation() throws IOException {
        // kind 白名单 fail-closed：候选校验阶段（loader 重读临时文件）即拒绝未知值，原文件不动。
        byte[] before = Files.readAllBytes(toml);
        GateException e = assertThrows(GateException.class,
                () -> writer.write(toml, Map.of("engine.kind", "prism")));
        assertTrue(e.getMessage().contains("validation failed")
                || e.getMessage().contains("engine.kind"), e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(toml));
    }

    @Test
    void backupKeepsPreviousContentAfterSuccessfulWrite() throws IOException {
        String before = Files.readString(toml, StandardCharsets.UTF_8);
        writer.write(toml, Map.of("web.port", 9999));
        Path bak = toml.resolveSibling("gate.toml.bak");
        assertTrue(Files.exists(bak), "a .bak must exist after a successful write");
        assertEquals(before, Files.readString(bak, StandardCharsets.UTF_8));
        assertTrue(Files.readString(toml, StandardCharsets.UTF_8).contains("port = 9999"));
    }

    @Test
    void crlfLineEndingsPreserved() throws IOException {
        Files.writeString(toml, sampleToml("\r\n"), StandardCharsets.UTF_8);
        writer.write(toml, Map.of("web.port", 8100));
        String text = Files.readString(toml, StandardCharsets.UTF_8);
        assertTrue(text.contains("\r\n"), "CRLF style must survive the rewrite: " + text);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                assertTrue(i > 0 && text.charAt(i - 1) == '\r', "bare LF introduced at index " + i);
            }
        }
        assertTrue(text.contains("port = 8100"), text);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
