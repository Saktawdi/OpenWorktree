package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.application.util.MiniJson;
import gate.domain.error.GateException;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two config dialects are the load-bearing part of MCP provisioning: opencode wants the whole
 * command line as ONE array with env under "environment", Claude wants command/args split with env
 * under "env". A wrong shape fails silently at spawn time (the agent just never sees
 * presubmit_create), so the exact schemas are pinned here. The entrypoint choice is pinned too:
 * gate.bootstrap.McpServeApp preferred (survives IDE-launched backends), gate.cli.GateApp legacy
 * fallback, and a loud refusal when neither resolves.
 */
class GateMcpProvisioningTest {

    @Test
    void serveArgvPrefersBootstrapEntrypointWhenResolvable() {
        List<String> argv = GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml"), "cp",
                name -> name.equals(GateMcpProvisioning.BOOTSTRAP_SERVE_MAIN));
        assertEquals(GateMcpProvisioning.BOOTSTRAP_SERVE_MAIN, argv.get(3));
        assertEquals("--config", argv.get(4));
        assertTrue(argv.get(5).endsWith("gate.toml"), "toml must be passed to the child");
        assertTrue(argv.get(0).contains("java"), "must launch the running JVM, not a PATH lookup");
        assertTrue(Path.of(argv.get(2)).isAbsolute() && argv.get(2).endsWith("cp"),
                "classpath must be inherited and re-anchored to absolute: " + argv.get(2));
    }

    /**
     * Native-image deployment: the binary re-invokes ITSELF with the {@code mcp} subcommand — no
     * java executable, no classpath inheritance. A blank executable path must fail loudly: a
     * written-but-unstartable child config once cost every session its presubmit_create.
     */
    @Test
    void nativeServeArgvReinvokesSelfWithMcpSubcommand() {
        List<String> argv = GateMcpProvisioning.nativeServeArgv(Path.of("conf/gate.toml"),
                "/opt/ow/bin/ow-native");
        assertEquals("/opt/ow/bin/ow-native", argv.get(0));
        assertEquals("mcp", argv.get(1));
        assertEquals("--config", argv.get(2));
        assertTrue(Path.of(argv.get(3)).isAbsolute() && argv.get(3).endsWith("gate.toml"),
                "toml must be re-anchored to absolute for the child's cwd: " + argv.get(3));
    }

    @Test
    void nativeServeArgvFailsLoudlyWithoutExecutablePath() {
        GateException e = assertThrows(GateException.class,
                () -> GateMcpProvisioning.nativeServeArgv(Path.of("conf/gate.toml"), " "));
        assertTrue(e.getMessage().contains("ProcessHandle"));
    }

    @Test
    void serveArgvFallsBackToGateAppWhenOnlyItIsResolvable() {
        List<String> argv = GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml"), "cp",
                name -> name.equals(GateMcpProvisioning.GATE_APP_MAIN));
        assertEquals(GateMcpProvisioning.GATE_APP_MAIN, argv.get(3));
        assertEquals("mcp", argv.get(4));
        assertEquals("serve", argv.get(5));
        assertEquals("--config", argv.get(6));
        assertTrue(argv.get(7).endsWith("gate.toml"), "toml must be passed to the child");
    }

    @Test
    void serveArgvFailsLoudlyWhenNoEntrypointResolves() {
        // An IDE-launched web backend carries no gate-cli (ArchUnit §2.1) — that silent degradation
        // once cost every session its presubmit_create. Provisioning must refuse, not emit a
        // dead config.
        GateException e = assertThrows(GateException.class, () ->
                GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml"), "cp", name -> false));
        assertTrue(e.getMessage().contains("presubmit_create"), e.getMessage());
        assertTrue(e.getMessage().contains(GateMcpProvisioning.BOOTSTRAP_SERVE_MAIN), e.getMessage());
    }

    @Test
    void classpathEntriesAreReAnchoredToAbsolutePaths() {
        // The MCP child runs with the ticket clone as cwd; relative classpath entries from a
        // `java @args`-launched backend would only resolve there, not from the clone.
        String cp = GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml"),
                "rel-classes" + File.pathSeparator + Path.of("abs.jar").toAbsolutePath(),
                name -> true).get(2);
        for (String entry : cp.split(File.pathSeparator)) {
            assertTrue(Path.of(entry).isAbsolute(), "entry must be absolute: " + entry);
        }
    }

    @Test
    void productionOverloadMatchesRealResolvability() {
        boolean anyEntrypoint = GateMcpProvisioning.resolvable(GateMcpProvisioning.BOOTSTRAP_SERVE_MAIN)
                || GateMcpProvisioning.resolvable(GateMcpProvisioning.GATE_APP_MAIN);
        if (anyEntrypoint) {
            assertFalse(GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml")).isEmpty());
        } else {
            // gate-adapters test JVMs carry neither entrypoint: the overload must throw.
            assertThrows(GateException.class, () -> GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml")));
        }
    }

    @Test
    void opencodeSchemaIsCommandArrayPlusEnvironment() {
        String json = GateMcpProvisioning.opencodeConfigJson(
                List.of("C:/java/bin/java.exe", "-cp", "cp", "gate.cli.GateApp", "mcp", "serve"),
                "tok");
        assertTrue(json.startsWith("{\"mcp\":{\"gate\":{\"type\":\"local\",\"command\":["));
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("\"environment\":{\"GATE_DOMAIN_TOKEN\":\"tok\"}"));
        assertFalse(json.contains("\"env\":"), "opencode does not use Claude's env field name");
        assertFalse(json.contains("\"args\":"), "opencode does not split command/args");
    }

    @Test
    void claudeSchemaIsCommandArgsSplitPlusEnv() {
        String json = GateMcpProvisioning.claudeConfigJson(
                List.of("C:/java/bin/java.exe", "-cp", "cp", "gate.cli.GateApp", "mcp", "serve"),
                "tok");
        assertTrue(json.startsWith("{\"mcpServers\":{\"gate\":{\"command\":\""));
        assertTrue(json.contains("\"args\":[\"-cp\",\"cp\""));
        assertTrue(json.contains("\"env\":{\"GATE_DOMAIN_TOKEN\":\"tok\"}"));
        assertFalse(json.contains("\"environment\":"), "claude does not use opencode's field name");
    }

    @Test
    void tokensAndPathsAreEscaped() {
        String json = GateMcpProvisioning.opencodeConfigJson(
                List.of("C:\\x\\java.exe"), "to\"k\n\\x");
        assertTrue(json.contains("to\\\"k\\n\\\\x"), "quote/newline/backslash must be escaped");
    }

    @Test
    void opencodeConfigParsesAndCarriesTokenThroughStructure() {
        // Substring checks above cannot catch unbalanced braces (a missing close brace once shipped
        // and made opencode reject the config with ConfigJsonError CloseBraceExpected -> 503), so
        // both dialects must round-trip through a real parser.
        Map<?, ?> opencode = assertInstanceOf(Map.class,
                MiniJson.parse(GateMcpProvisioning.opencodeConfigJson(
                        List.of("C:/java/bin/java.exe", "-cp", "cp", "Main", "mcp", "serve"), "tok")));
        assertEquals("tok", environmentToken(opencode, "mcp", "environment"));
        Map<?, ?> claude = assertInstanceOf(Map.class,
                MiniJson.parse(GateMcpProvisioning.claudeConfigJson(
                        List.of("C:/java/bin/java.exe", "-cp", "cp", "Main", "mcp", "serve"), "tok")));
        assertEquals("tok", environmentToken(claude, "mcpServers", "env"));
    }

    private static String environmentToken(Map<?, ?> root, String serverKey, String envKey) {
        Map<?, ?> servers = assertInstanceOf(Map.class, root.get(serverKey));
        Map<?, ?> gate = assertInstanceOf(Map.class, servers.get("gate"));
        Map<?, ?> env = assertInstanceOf(Map.class, gate.get(envKey));
        return String.valueOf(env.get("GATE_DOMAIN_TOKEN"));
    }
}
