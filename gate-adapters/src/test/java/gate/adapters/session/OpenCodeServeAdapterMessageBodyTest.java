package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import gate.domain.session.AgentCli;
import gate.domain.session.AgentConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Message-body construction for POST /session/{id}/message: parts always present, model when
 * parseable, explicit {@code agent} only when an {@code --agent=} / {@code agent=} extra flag is set.
 */
class OpenCodeServeAdapterMessageBodyTest {

    private static AgentConfig config(String model, List<String> extraFlags) {
        return new AgentConfig("cfg-1", "Cfg", AgentCli.OPENCODE, null, model, null, extraFlags, null);
    }

    @Test
    void bodyCarriesPartsModelAndExplicitAgent() {
        String body = OpenCodeServeAdapter.messageBody(
                config("own/deepseek-v4-flash", List.of("--agent=build")), "hi");
        assertEquals(
                "{\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"model\":{\"providerID\":\"own\",\"modelID\":\"deepseek-v4-flash\"},"
                        + "\"agent\":\"build\"}",
                body);
    }

    @Test
    void bareAgentPrefixIsAccepted() {
        assertEquals("plan", OpenCodeServeAdapter.agentFlag(config(null, List.of("agent=plan"))));
    }

    @Test
    void withoutFlagsThereIsNoAgentFieldAndNoTrailingComma() {
        String body = OpenCodeServeAdapter.messageBody(config("p/m", List.of()), "hi");
        assertFalse(body.contains("\"agent\""), body);
        assertEquals(
                "{\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"model\":{\"providerID\":\"p\",\"modelID\":\"m\"}}",
                body);
        assertNull(OpenCodeServeAdapter.agentFlag(config(null, null)));
    }
}
