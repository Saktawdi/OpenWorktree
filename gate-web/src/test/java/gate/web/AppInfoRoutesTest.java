package gate.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 「应用设置」路由挂载（V5 web console）：/api/app/info 必须在鉴权之后、带 HUMAN token 可读，
 * 且仓库坐标与产品名固定指向本项目的 GitHub 仓库。
 *
 * <p>update-check 路由会真的访问 GitHub，不在 HTTP 层测——判定逻辑与 GitHub 出口已在
 * {@code gate.web.service.AppInfoServiceTest} 用假传输覆盖。
 */
class AppInfoRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        base = "http://127.0.0.1:" + server.port();
        token = harness.humanToken();
        client = HttpClient.newHttpClient();
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

    private HttpResponse<String> httpGet(String path, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void appInfoRequiresHumanToken() throws Exception {
        assertEquals(401, httpGet("/api/app/info", null).statusCode(), "missing token must 401");
    }

    @Test
    void appInfoExposesVersionAndRepoCoordinates() throws Exception {
        HttpResponse<String> res = httpGet("/api/app/info", token);
        assertEquals(200, res.statusCode(), res.body());
        Map<String, Object> body = JSON.readValue(res.body(), Map.class);
        assertEquals("OpenWorktree", body.get("name"));
        Object version = body.get("version");
        assertTrue(version != null && !String.valueOf(version).isBlank(), "version must be present");
        assertFalse(String.valueOf(version).contains("${"),
                "unfiltered placeholder must never leak: " + version);
        assertEquals("https://github.com/Saktawdi/OpenWorktree", body.get("repo_url"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree/releases", body.get("releases_url"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree/releases/latest", body.get("download_url"));
    }
}
