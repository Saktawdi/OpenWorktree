package gate.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the {@code gate.bootstrap.McpServeApp} entrypoint exactly the way a provisioned agent
 * child runs: {@code --config gate.toml} + the domain token, then the real MCP handshake. This is
 * the surface an agent session sees, and the proof that the bootstrap entrypoint (chosen by
 * GateMcpProvisioning for backends that carry no gate-cli classes) wires the same tool registry as
 * {@code gate mcp serve}.
 */
class McpServeAppTest {

    @TempDir
    Path dir;

    @Test
    void handshakeListsAgentDomainTools() throws Exception {
        Path toml = writeToml();
        String stdin = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = McpServeApp.run(List.of("--config", toml.toString()), "tok",
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);

        List<String> lines = stdout.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).toList();
        assertEquals(2, lines.size(), "one JSON-RPC response per request: " + lines);

        Map<?, ?> init = cast(MiniJson.parse(lines.get(0)));
        assertEquals(1L, init.get("id"));
        Map<?, ?> initResult = cast(init.get("result"));
        assertEquals("2024-11-05", initResult.get("protocolVersion"));

        Map<?, ?> toolsResp = cast(MiniJson.parse(lines.get(1)));
        Map<?, ?> toolsResult = cast(toolsResp.get("result"));
        List<?> tools = castList(toolsResult.get("tools"));
        List<String> names = tools.stream().map(t -> String.valueOf(cast(t).get("name"))).toList();
        assertTrue(names.contains("presubmit_create"), "agent tool must be listed: " + names);
        assertTrue(names.contains("presubmit_get_diff"), names.toString());
        assertTrue(names.contains("review_result_get"), names.toString());
        assertTrue(names.contains("session_read"), "session_read must be listed: " + names);
        assertTrue(names.contains("ticket_edit"), "ticket_edit must be listed: " + names);
        assertTrue(names.contains("commit_and_publish"), "human tools ride the same registry: " + names);
    }

    /**
     * {@code session_read} through the bootstrap entrypoint: with a real credential the call must
     * reach the session store — this DB has no session rows, so the structured "no such session"
     * domain error is the proof the repository is wired (a missing wiring would surface as an
     * internal error instead). The domain check runs first and is covered by the handshake test.
     */
    @Test
    void session_read_is_wired_to_the_session_store() throws Exception {
        Path toml = writeToml();
        // Seed a credential into the same gate-home the child will resolve from this toml.
        String token;
        GateRuntime runtime = new GateRuntime(
                new gate.adapters.config.TomlGateConfigLoader().load(toml), "git");
        token = runtime.credentials().issueHumanToken(java.time.Instant.now());

        String stdin = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"session_read","arguments":{"session_id":"s-missing"}}}
                """;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = McpServeApp.run(List.of("--config", toml.toString()), token,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);

        Map<?, ?> resp = cast(MiniJson.parse(stdout.toString(StandardCharsets.UTF_8)));
        Map<?, ?> error = cast(resp.get("error"));
        assertEquals("no such session: s-missing", error.get("message"));
        Map<?, ?> data = cast(error.get("data"));
        assertEquals("domain", data.get("layer"));
        assertEquals("session_read", data.get("tool"));
    }

    @Test
    void refusesWithoutDomainToken() throws Exception {
        GateException e = assertThrows(GateException.class, () ->
                McpServeApp.run(List.of("--config", writeToml().toString()), "  ",
                        new ByteArrayInputStream(new byte[0]),
                        nopOut(), new PrintStream(OutputStream.nullOutputStream())));
        assertEquals(GateErrorCode.GATE_ERROR_CONFIG, e.code());
        assertTrue(e.getMessage().contains("GATE_DOMAIN_TOKEN"));
    }

    @Test
    void refusesWithoutConfigArgument() {
        GateException e = assertThrows(GateException.class, () ->
                McpServeApp.run(List.of(), "tok", new ByteArrayInputStream(new byte[0]),
                        nopOut(), nopOut()));
        assertEquals(GateErrorCode.USAGE, e.code());
    }

    @Test
    void refusesUnknownArguments() {
        GateException e = assertThrows(GateException.class, () ->
                McpServeApp.run(List.of("--config", "x.toml", "--bogus"), "tok",
                        new ByteArrayInputStream(new byte[0]), nopOut(), nopOut()));
        assertEquals(GateErrorCode.USAGE, e.code());
        assertTrue(e.getMessage().contains("--bogus"));
    }

    private Path writeToml() throws Exception {
        Path gateHome = dir.resolve("gate-home");
        Files.createDirectories(gateHome);
        Path toml = dir.resolve("gate.toml");
        Files.writeString(toml, String.join("\n",
                "schema_version = 2",
                "project = \"mcp-serve-test\"",
                "gate_home = \"" + gateHome.toString().replace('\\', '/') + "\"",
                "target_ref_whitelist = [\"refs/heads/main\"]",
                "",
                "[gate_identity]",
                "name = \"gate\"",
                "email = \"gate@localhost\"",
                ""), StandardCharsets.UTF_8);
        return toml;
    }

    private static PrintStream nopOut() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static Map<?, ?> cast(Object o) {
        assertNotNull(o);
        return (Map<?, ?>) o;
    }

    private static List<?> castList(Object o) {
        return (List<?>) o;
    }
}
