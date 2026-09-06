package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * S1 error-model coverage (执行文档-后端-web §4.4, §10 S1 交付物 "错误码映射").
 *
 * <p>Every failure returns the standard envelope {@code {error_code, error, message, detail}} with
 * the HTTP status dictated by the §4.4 GateErrorCode→HTTP table. These cases exercise the three
 * distinct error sources: the router's 404 for an unknown path, a {@link
 * gate.domain.error.GateException} thrown inside a route (USAGE→400, REJECT_PRECONDITION→422), and
 * the inline 405 for a whitelisted endpoint invoked with the wrong method.
 */
@Tag("slow")
class WebErrorMappingTest {

    private WebHarness harness;
    private WebServer server;
    private HttpClient client;
    private String base;
    private String token;

    @BeforeEach
    void setUp() {
        harness = new WebHarness();
        server = new WebServer(harness.components());
        server.start();
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
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
    void unknown_api_endpoint_is_404_with_error_envelope() throws Exception {
        HttpResponse<String> res = get("/api/does-not-exist");
        assertEquals(404, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"NOT_FOUND\""), res.body());
        assertTrue(res.body().contains("\"error_code\":"), res.body());
        assertTrue(res.body().contains("\"detail\":[]"), res.body());
    }

    @Test
    void non_object_body_on_ticket_create_is_400_usage() throws Exception {
        // A JSON array is not an object → USAGE (§4.4 → 400), with the standard envelope.
        HttpResponse<String> res = post("/api/tickets", "[1,2,3]");
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"USAGE\""), res.body());
        assertTrue(res.body().contains("\"error_code\":64"), res.body());
    }

    @Test
    void duplicate_ticket_create_is_400_usage() throws Exception {
        assertEquals(201, post("/api/tickets", "{\"ticket_no\":\"DUP-1\",\"title\":\"a\"}").statusCode());
        HttpResponse<String> again = post("/api/tickets", "{\"ticket_no\":\"DUP-1\",\"title\":\"b\"}");
        assertEquals(400, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"error\":\"USAGE\""), again.body());
    }

    @Test
    void invalid_priority_on_ticket_create_lists_the_broken_field_in_detail() throws Exception {
        // T-108: validation failures carry a per-field detail list (field, expected, actual) so
        // the caller can fix the request instead of guessing from prose.
        HttpResponse<String> res = post("/api/tickets", "{\"title\":\"t\",\"priority\":\"PX\"}");
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"USAGE\""), res.body());
        assertTrue(res.body().contains("\"detail\":[\"priority: invalid value"), res.body());
        assertTrue(res.body().contains("P0, P1, P2, P3"), res.body());
        assertTrue(res.body().contains("PX"), res.body());
    }

    @Test
    void missing_title_on_ticket_create_lists_the_broken_field_in_detail() throws Exception {
        HttpResponse<String> res = post("/api/tickets", "{\"priority\":\"P1\"}");
        assertEquals(400, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"USAGE\""), res.body());
        assertTrue(res.body().contains("title: missing required parameter"), res.body());
        assertTrue(res.body().contains("non-blank string"), res.body());
    }

    @Test
    void empty_diff_presubmit_maps_to_422_precondition() throws Exception {
        post("/api/tickets", "{\"ticket_no\":\"EMPTY-1\",\"title\":\"t\"}");
        // Nothing changed in the clone → tree == base tree → REJECT_PRECONDITION (§4.4 → 422).
        HttpResponse<String> res = post("/api/tickets/EMPTY-1/presubmit", "");
        assertEquals(422, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"REJECT_PRECONDITION\""), res.body());
    }

    @Test
    void auth_verify_wrong_method_is_405() throws Exception {
        // /api/auth/verify is on the token whitelist and handled inline; GET is rejected 405.
        HttpResponse<String> res = get("/api/auth/verify");
        assertEquals(405, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"error\":\"METHOD_NOT_ALLOWED\""), res.body());
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
}
