package gate.adapters.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void overrideModelAndVariantBeatConfigDefaults() {
        String body = OpenCodeServeAdapter.messageBody(
                config("own/deepseek-v4-flash", List.of()), "hi", "prov-x", "model-x", "high");
        assertEquals(
                "{\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"model\":{\"providerID\":\"prov-x\",\"modelID\":\"model-x\"},"
                        + "\"variant\":\"high\"}",
                body);
    }

    @Test
    void variantWithoutOverrideFallsBackToConfigModel() {
        String body = OpenCodeServeAdapter.messageBody(
                config("own/deepseek-v4-flash", List.of()), "hi", null, null, "max");
        assertEquals(
                "{\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"model\":{\"providerID\":\"own\",\"modelID\":\"deepseek-v4-flash\"},"
                        + "\"variant\":\"max\"}",
                body);
    }

    @Test
    void blankOrMissingVariantOmitsTheField() {
        String withBlank = OpenCodeServeAdapter.messageBody(
                config("p/m", List.of()), "hi", "px", "mx", " ");
        assertFalse(withBlank.contains("variant"), withBlank);
        String legacy = OpenCodeServeAdapter.messageBody(config("p/m", List.of()), "hi");
        assertFalse(legacy.contains("variant"), legacy);
    }

    @Test
    void attachmentsBecomeFilePartsWithDataUrls() {
        var attachment = new gate.ports.session.AgentSessionPort.Attachment(
                "截图.png", "image/png", "aGVsbG8=");
        String body = OpenCodeServeAdapter.messageBody(
                config("own/deepseek-v4-flash", List.of()), "看这张图",
                List.of(attachment), null, null, null);
        assertEquals(
                "{\"parts\":[{\"type\":\"text\",\"text\":\"看这张图\"},"
                        + "{\"type\":\"file\",\"mime\":\"image/png\",\"filename\":\"截图.png\","
                        + "\"url\":\"data:image/png;base64,aGVsbG8=\"}],"
                        + "\"model\":{\"providerID\":\"own\",\"modelID\":\"deepseek-v4-flash\"}}",
                body);
    }

    @Test
    void multipleAttachmentsAndEscapedFilenames() {
        var one = new gate.ports.session.AgentSessionPort.Attachment("a\"b.png", "image/png", "AA==");
        var two = new gate.ports.session.AgentSessionPort.Attachment(null, "image/jpeg", "BB=");
        String body = OpenCodeServeAdapter.messageBody(config(null, List.of()), "",
                List.of(one, two), "px", "mx", "high");
        assertTrue(body.contains("\"filename\":\"a\\\"b.png\""), body);
        assertTrue(body.contains("\"filename\":\"image\""), body);
        // 空正文也允许：附件本身构成有效回合。
        assertTrue(body.startsWith("{\"parts\":[{\"type\":\"text\",\"text\":\"\"},"), body);
        assertTrue(body.endsWith("\"variant\":\"high\"}"), body);
    }

    @Test
    void deliveryFieldIsIncludedWhenSpecified() {
        String body = OpenCodeServeAdapter.messageBody(config("p/m", List.of()), "steer me",
                List.of(), "prov", "mod", "var", "steer");
        assertTrue(body.contains("\"delivery\":\"steer\""), body);
    }
}
