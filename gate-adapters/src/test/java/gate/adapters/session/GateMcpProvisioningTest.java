package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two config dialects are the load-bearing part of MCP provisioning: opencode wants the whole
 * command line as ONE array with env under "environment", Claude wants command/args split with env
 * under "env". A wrong shape fails silently at spawn time (the agent just never sees
 * presubmit_create), so the exact schemas are pinned here.
 */
class GateMcpProvisioningTest {

    @Test
    void serveArgvLaunchesGateAppMcpServeWithConfig() {
        List<String> argv = GateMcpProvisioning.serveArgv(Path.of("conf/gate.toml"));
        assertEquals("gate.cli.GateApp", argv.get(3));
        assertEquals("mcp", argv.get(4));
        assertEquals("serve", argv.get(5));
        assertEquals("--config", argv.get(6));
        assertTrue(argv.get(7).endsWith("gate.toml"), "toml must be passed to the child");
        assertTrue(argv.get(0).contains("java"), "must launch the running JVM, not a PATH lookup");
        assertTrue(argv.get(2).length() > 0, "classpath of the running JVM must be inherited");
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
}
