package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * V18 project star/pin + manual drag ordering: starred toggles through the update endpoint and
 * reorder assigns dense sort_order values; listing order is starred first, then sort_order.
 */
@Tag("slow")
class ProjectStarOrderApiTest {

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

    private String create(String name) throws Exception {
        HttpResponse<String> res = post("/api/projects",
                "{\"name\":\"" + name + "\",\"workspace_path\":\""
                        + harness.root().resolve("ws-" + name.toLowerCase()).toString().replace("\\", "\\\\") + "\"}");
        assertEquals(201, res.statusCode(), res.body());
        return extract(res.body(), "id");
    }

    @Test
    void star_toggles_via_update_and_pins_listing() throws Exception {
        String alpha = create("Alpha");
        String beta = create("Beta");

        HttpResponse<String> star = put("/api/projects/" + alpha, "{\"starred\":true}");
        assertEquals(200, star.statusCode(), star.body());
        assertTrue(star.body().contains("\"starred\":true"), star.body());
        assertTrue(star.body().contains("\"sort_order\":"), "listing must expose sort_order: " + star.body());

        HttpResponse<String> list = get("/api/projects");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().indexOf("Alpha") < list.body().indexOf("Beta"),
                "starred project must sort first: " + list.body());

        HttpResponse<String> unstar = put("/api/projects/" + alpha, "{\"starred\":false}");
        assertEquals(200, unstar.statusCode(), unstar.body());
        assertTrue(unstar.body().contains("\"starred\":false"), unstar.body());

        HttpResponse<String> badStar = put("/api/projects/" + alpha, "{\"starred\":\"yes\"}");
        assertTrue(badStar.statusCode() >= 400, "non-boolean starred must be rejected: " + badStar.body());
    }

    @Test
    void reorder_assigns_dense_sort_order_and_listing_follows() throws Exception {
        String alpha = create("Alpha");
        String beta = create("Beta");
        String gamma = create("Gamma");

        HttpResponse<String> reorder = post("/api/projects/reorder",
                "{\"order\":[\"" + gamma + "\",\"" + alpha + "\",\"" + beta + "\"]}");
        assertEquals(200, reorder.statusCode(), reorder.body());

        HttpResponse<String> list = get("/api/projects");
        String body = list.body();
        assertEquals(200, list.statusCode(), body);
        int gammaPos = body.indexOf("\"id\":\"" + gamma + "\"");
        int alphaPos = body.indexOf("\"id\":\"" + alpha + "\"");
        int betaPos = body.indexOf("\"id\":\"" + beta + "\"");
        assertTrue(gammaPos >= 0 && gammaPos < alphaPos && alphaPos < betaPos,
                "listing must follow the submitted order: " + body);

        // 星标覆盖手动顺序：置顶组永远在最前
        assertTrue(put("/api/projects/" + beta, "{\"starred\":true}").statusCode() == 200);
        HttpResponse<String> starredList = get("/api/projects");
        String starredBody = starredList.body();
        assertTrue(starredBody.indexOf("\"id\":\"" + beta + "\"") < starredBody.indexOf("\"id\":\"" + gamma + "\""),
                "starred project must jump above manual order: " + starredBody);
    }

    @Test
    void reorder_rejects_unknown_ids_and_non_array_order() throws Exception {
        String alpha = create("Alpha");
        assertTrue(post("/api/projects/reorder", "{\"order\":[\"" + alpha + "\",\"ghost\"]}")
                .statusCode() >= 400, "unknown id must be rejected");
        assertTrue(post("/api/projects/reorder", "{\"order\":\"" + alpha + "\"}")
                .statusCode() >= 400, "non-array order must be rejected");
        assertTrue(post("/api/projects/reorder", "{}").statusCode() >= 400,
                "missing order must be rejected");
    }

    @Test
    void fresh_projects_get_tail_sort_order() throws Exception {
        String alpha = create("Alpha");
        HttpResponse<String> list = get("/api/projects");
        assertTrue(list.body().contains("\"sort_order\":1"),
                "first project starts at sort_order 1: " + list.body());
        // 已有 sort_order 后新建的项目排在末尾（max+1），不会插到已排序项目前面
        HttpResponse<String> reorder = post("/api/projects/reorder", "{\"order\":[\"" + alpha + "\"]}");
        assertEquals(200, reorder.statusCode(), reorder.body());
        String beta = create("Beta");
        HttpResponse<String> after = get("/api/projects");
        assertTrue(after.body().indexOf("\"id\":\"" + alpha + "\"") < after.body().indexOf("\"id\":\"" + beta + "\""),
                "newly registered project must append after ordered ones: " + after.body());
    }

    private static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\":\"");
        if (i < 0) {
            throw new AssertionError("field " + field + " missing in " + json);
        }
        int start = i + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
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

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
