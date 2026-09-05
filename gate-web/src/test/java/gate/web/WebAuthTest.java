package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.config.GateConfig;
import gate.web.security.AuthFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * S0 acceptance A13 (执行文档-后端-web §9.5): auth + binding.
 *
 * <ul>
 *   <li>no token → {@code /api/status} 401 (here: any non-whitelisted /api path)</li>
 *   <li>valid HUMAN token → accepted by the auth filter (verify endpoint 200)</li>
 *   <li>{@code /api/health} open without token</li>
 *   <li>wrong Host/Origin → 403 (DNS-rebinding guard)</li>
 *   <li>{@code web.bind = 0.0.0.0} refused at config construction (fail-closed, exit 22 path)</li>
 * </ul>
 */
@Tag("slow")
class WebAuthTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void health_is_open_without_token() throws Exception {
        HttpResponse<String> res = get("/api/health", null, null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"status\":\"ok\""), res.body());
    }

    @Test
    void non_whitelisted_api_without_token_is_401() throws Exception {
        // No route is wired at S0 beyond health/auth; an authorised request 404s, an unauthorised
        // one is rejected by the filter BEFORE routing → 401. This proves the filter runs first.
        HttpResponse<String> res = get("/api/status", null, null);
        assertEquals(401, res.statusCode(), res.body());
    }

    @Test
    void valid_human_token_passes_the_filter() throws Exception {
        // With a valid token the filter lets it through to the S1 /api/status route → 200,
        // distinct from the 401 an unauthorised request gets before routing.
        HttpResponse<String> res = get("/api/status", harness.humanToken(), null);
        assertEquals(200, res.statusCode(), res.body());
    }

    @Test
    void auth_verify_accepts_valid_token_and_rejects_bad_one() throws Exception {
        HttpResponse<String> ok = post("/api/auth/verify",
                "{\"token\":\"" + harness.humanToken() + "\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"domain\":\"HUMAN\""), ok.body());

        HttpResponse<String> bad = post("/api/auth/verify", "{\"token\":\"deadbeef\"}");
        assertEquals(401, bad.statusCode(), bad.body());
    }

    @Test
    void wrong_origin_header_is_rejected() throws Exception {
        // The JDK HttpClient forbids setting Host, so we exercise the same DNS-rebinding guard via
        // the Origin header, which AuthFilter checks with the identical whitelist.
        HttpResponse<String> res = get("/api/status", harness.humanToken(), "http://evil.example.com");
        assertEquals(403, res.statusCode(), res.body());
    }

    @Test
    void bind_0_0_0_0_is_refused_at_config() {
        Path tokenFile = harness.root().resolve("gate-home").resolve("web-token");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new GateConfig.WebConfig("0.0.0.0", 4097, List.of("127.0.0.1"), tokenFile));
        assertTrue(ex.getMessage().contains("loopback"), ex.getMessage());
    }

    private HttpResponse<String> get(String path, String token, String originOverride) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (originOverride != null) {
            b.header("Origin", originOverride);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
