package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gate.domain.session.AgentCli;
import gate.domain.session.Session;
import gate.domain.session.SessionStatus;
import gate.domain.session.SessionUsage;
import gate.web.service.SessionModelCatalog;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 会话内实时切换模型/推理强度 (OpenChamber-style per-session picker):
 * POST /api/sessions/{id}/model persists the override, GET /api/sessions/{id}/models proxies
 * the session's opencode serve {@code /config/providers}, and the send body carries the switch.
 */
class SessionModelSwitchTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;
    private HttpServer fakeServe;
    private int fakeServePort;
    private int deadPort;

    @BeforeEach
    void setUp() throws Exception {
        // The switch test drives persistence through a fake session port: the real adapter would
        // spawn a serve on the harness port range, which collides with whatever opencode instances
        // happen to run on the dev machine (assertPortFree refuses exactly that).
        SessionOrchestrationTest.FakeAgentSessionPort fake = new SessionOrchestrationTest.FakeAgentSessionPort();
        harness = new WebHarness("git", "127.0.0.1", fake);
        fake.bind(harness.components().sessionRepository(), harness.components().clock());
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();

        // A stand-in for the session's opencode serve instance exposing /config/providers,
        // shaped exactly like OpenCode 1.x (models keyed by id, variants as an object map).
        fakeServe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServePort = fakeServe.getAddress().getPort();
        fakeServe.createContext("/config/providers", exchange -> {
            byte[] body = ("{\"providers\":[{"
                    + "\"id\":\"prov-x\",\"name\":\"Provider X\","
                    + "\"models\":{"
                    + "\"model-x\":{\"id\":\"model-x\",\"name\":\"Model X\","
                    + "\"variants\":{\"high\":{},\"low\":{}}},"
                    + "\"model-y\":{\"id\":\"model-y\",\"name\":\"Model Y\",\"variants\":{}}"
                    + "}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeServe.start();
    }

    @AfterEach
    void tearDown() {
        if (fakeServe != null) {
            fakeServe.stop(0);
        }
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void model_switch_persists_and_roundtrips_over_http() throws Exception {
        assertEquals(201, post("/api/agent-configs", """
                {"id":"oc-switch","name":"OC Switch","cli":"OPENCODE","provider_id":"manual",
                 "model":"own/default-model","extra_flags":[],"description":"test"}
                """).statusCode());
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"SW-1\",\"title\":\"switch\"}").statusCode());
        HttpResponse<String> created = post("/api/tickets/SW-1/sessions",
                "{\"agent_config_id\":\"oc-switch\"}");
        assertEquals(201, created.statusCode(), created.body());
        String sid = sessionId(created.body());
        assertNull(jsonField(created.body(), "override_provider"), "fresh session has no override");

        // Switch model + reasoning effort mid-session.
        HttpResponse<String> switched = post("/api/sessions/" + sid + "/model",
                "{\"provider_id\":\"prov-x\",\"model_id\":\"model-x\",\"variant\":\"high\"}");
        assertEquals(200, switched.statusCode(), switched.body());
        assertEquals("prov-x", jsonField(switched.body(), "override_provider"));
        assertEquals("model-x", jsonField(switched.body(), "override_model"));
        assertEquals("high", jsonField(switched.body(), "override_variant"));

        // Persisted in the real repository (the next prompt_async send reads it from there).
        Session persisted = harness.components().sessionRepository().find(sid).orElseThrow();
        assertEquals("prov-x", persisted.overrideProvider());
        assertEquals("model-x", persisted.overrideModel());
        assertEquals("high", persisted.overrideVariant());

        // Clearing: variant only first — the model pair must survive.
        assertEquals(200, post("/api/sessions/" + sid + "/model", "{\"variant\":\"\"}").statusCode());
        Session halfCleared = harness.components().sessionRepository().find(sid).orElseThrow();
        assertNull(halfCleared.overrideVariant());
        assertEquals("prov-x", halfCleared.overrideProvider());
        assertEquals("model-x", halfCleared.overrideModel());
        // …then an all-blank body clears the whole override back to config defaults.
        // ({ } keeps everything under tri-state semantics.)
        assertEquals(200, post("/api/sessions/" + sid + "/model",
                "{\"provider_id\":\"\",\"model_id\":\"\",\"variant\":\"\"}").statusCode());
        Session cleared = harness.components().sessionRepository().find(sid).orElseThrow();
        assertNull(cleared.overrideProvider());
        assertNull(cleared.overrideModel());
        assertNull(cleared.overrideVariant());

        // Half a pair is rejected (USAGE).
        assertTrue(post("/api/sessions/" + sid + "/model", "{\"provider_id\":\"prov-x\"}")
                .statusCode() >= 400);

        // Unknown session -> USAGE error.
        assertTrue(post("/api/sessions/nope/model", "{}").statusCode() >= 400);
    }

    @Test
    void models_endpoint_proxies_the_serve_catalog_reduced_for_the_picker() throws Exception {
        insertSessionWithPort("sess-catalog-1", fakeServePort);
        HttpResponse<String> res = get("/api/sessions/sess-catalog-1/models");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"source\":\"serve\""), res.body());
        assertTrue(res.body().contains("\"id\":\"prov-x\""), res.body());
        // Variants are reduced to their sorted keys.
        assertTrue(res.body().contains("\"variants\":[\"high\",\"low\"]"), res.body());
        // Models without variants render an empty list.
        assertTrue(res.body().contains("\"id\":\"model-y\""), res.body());
    }

    @Test
    void models_endpoint_reports_a_claude_session_as_catalogless() throws Exception {
        insertSessionWithPort("sess-claude-1", -1);
        HttpResponse<String> res = get("/api/sessions/sess-claude-1/models");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"source\":\"no-server\""), res.body());
        assertTrue(res.body().contains("\"providers\":[]"), res.body());
    }

    /** A stale serve port (process died) degrades to an empty catalog, not a 503. */
    @Test
    void models_endpoint_degrades_gracefully_when_serve_is_down() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0,
                1, java.net.InetAddress.getByName("127.0.0.1"))) {
            deadPort = socket.getLocalPort();
        }
        insertSessionWithPort("sess-dead-1", deadPort);
        HttpResponse<String> res = get("/api/sessions/sess-dead-1/models");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"source\":\"unreachable\""), res.body());
        assertTrue(res.body().contains("\"providers\":[]"), res.body());
    }

    @Test
    void catalog_reducer_is_total_over_odd_payloads() {
        Map<String, Object> out = SessionModelCatalog.reduce("not json at all");
        assertTrue(((java.util.List<?>) out.get("providers")).isEmpty());

        Map<String, Object> out2 = SessionModelCatalog.reduce(
                "{\"providers\":[{\"id\":\"p1\",\"models\":{\"m1\":{\"id\":\"m1\"}}}]}");
        @SuppressWarnings("unchecked")
        Map<String, Object> provider =
                ((java.util.List<Map<String, Object>>) out2.get("providers")).get(0);
        assertEquals("p1", provider.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> model =
                ((java.util.List<Map<String, Object>>) provider.get("models")).get(0);
        assertEquals("m1", model.get("id"));
        assertTrue(((java.util.List<?>) model.get("variants")).isEmpty());
        assertEquals(false, model.get("image_input"), "unknown capability defaults to false");
    }

    /** 需求①：目录为每个模型标注图片输入能力（modalities.input 优先，attachment 兜底）。 */
    @Test
    void image_input_is_derived_from_modalities_then_attachment_flag() {
        Map<String, Object> out = SessionModelCatalog.reduce("{\"providers\":[{\"id\":\"p\","
                + "\"models\":{"
                + "\"m-mod\":{\"id\":\"m-mod\",\"modalities\":{\"input\":[\"text\",\"image\"]}},"
                + "\"m-text\":{\"id\":\"m-text\",\"modalities\":{\"input\":[\"text\"]}},"
                + "\"m-att\":{\"id\":\"m-att\",\"attachment\":true},"
                + "\"m-none\":{\"id\":\"m-none\"}"
                + "}}]}");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> models =
                ((java.util.List<Map<String, Object>>) out.get("providers")).get(0)
                        .get("models") instanceof java.util.List<?> l
                        ? (java.util.List<Map<String, Object>>) l : java.util.List.of();
        assertEquals(true, byId(models, "m-mod").get("image_input"));
        assertEquals(false, byId(models, "m-text").get("image_input"));
        assertEquals(true, byId(models, "m-att").get("image_input"));
        assertEquals(false, byId(models, "m-none").get("image_input"));
    }

    /**
     * T-112 回归：opencode serve 实际返回的是归一化的 capabilities 块
     * （input 为逐模态布尔表，attachment 在 capabilities 内），不是 models.dev 原始
     * modalities/顶层 attachment —— 旧实现两个键都取不到，导致所有模型被判为不支持图片。
     */
    @Test
    void image_input_reads_the_serve_capabilities_block() {
        Map<String, Object> out = SessionModelCatalog.reduce("{\"providers\":[{\"id\":\"goddamn\","
                + "\"models\":{"
                // 实测 serve payload（gemini-3.7-flash）：input.image=true → 支持图片
                + "\"gemini-3.7-flash\":{\"id\":\"gemini-3.7-flash\",\"capabilities\":{"
                + "\"temperature\":true,\"reasoning\":true,\"attachment\":true,\"toolcall\":true,"
                + "\"input\":{\"text\":true,\"audio\":true,\"image\":true,\"video\":true,\"pdf\":true}}},"
                // 实测 serve payload（deepseek-v4-flash-0731）：attachment=true 但 input.image=false
                // → input 表在场时以其为准，纯文本模型不得放行
                + "\"deepseek-v4-flash-0731\":{\"id\":\"deepseek-v4-flash-0731\",\"capabilities\":{"
                + "\"temperature\":true,\"reasoning\":true,\"attachment\":true,\"toolcall\":true,"
                + "\"input\":{\"text\":true,\"audio\":false,\"image\":false,\"video\":false,\"pdf\":false}}},"
                // 无 input 表时回退 capabilities.attachment
                + "\"m-caps-att\":{\"id\":\"m-caps-att\",\"capabilities\":{\"attachment\":true}},"
                + "\"m-caps-none\":{\"id\":\"m-caps-none\",\"capabilities\":{\"toolcall\":true}}"
                + "}}]}");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> models =
                ((java.util.List<Map<String, Object>>) out.get("providers")).get(0)
                        .get("models") instanceof java.util.List<?> l
                        ? (java.util.List<Map<String, Object>>) l : java.util.List.of();
        assertEquals(true, byId(models, "gemini-3.7-flash").get("image_input"));
        assertEquals(false, byId(models, "deepseek-v4-flash-0731").get("image_input"),
                "input map wins over attachment=true");
        assertEquals(true, byId(models, "m-caps-att").get("image_input"));
        assertEquals(false, byId(models, "m-caps-none").get("image_input"));
    }

    private static Map<String, Object> byId(java.util.List<Map<String, Object>> models, String id) {
        return models.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    /** Inserts an opencode session row directly so no real CLI is needed (creates FK parents). */
    private void insertSessionWithPort(String id, int port) throws Exception {
        assertEquals(201, post("/api/agent-configs", """
                {"id":"oc-switch","name":"OC Switch","cli":"OPENCODE","provider_id":"manual",
                 "model":"own/default-model","extra_flags":[],"description":"test"}
                """).statusCode());
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"SW-CAT\",\"title\":\"catalog\"}")
                .statusCode());
        Path clone = harness.root().resolve("clones").resolve(id);
        harness.components().sessionRepository().insert(new Session(
                id, "SW-CAT", "oc-switch", AgentCli.OPENCODE, SessionStatus.ACTIVE,
                "cli-" + id, clone.toString(), port, Instant.now(), null,
                SessionUsage.EMPTY, null, false));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static String sessionId(String body) {
        return String.valueOf(((Map<String, Object>) gate.application.util.MiniJson.parse(body.trim())).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String jsonField(String body, String field) {
        Object v = ((Map<String, Object>) gate.application.util.MiniJson.parse(body.trim())).get(field);
        return v == null ? null : String.valueOf(v);
    }
}
