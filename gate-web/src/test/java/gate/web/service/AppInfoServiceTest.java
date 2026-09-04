package gate.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * 应用信息与更新检查的判定逻辑（设置中心「应用设置」页的数据源）。
 *
 * <p>GitHub 出口全部走注入的假 {@link AppInfoService.HttpTransport}，不出网；重点覆盖
 * 三态判定（有更新 / 已是最新 / 先行 beta）、无 Release 时的 tags 回退、网络失败降级为
 * ok:false 数据（而非异常），以及结果缓存对 GitHub 匿名配额的保护。
 */
class AppInfoServiceTest {

    // ── 版本号数字核心与比较 ──

    @Test
    void versionCoreStripsSuffixAndPrefix() {
        assertEquals("[0, 1, 0]", java.util.Arrays.toString(AppInfoService.versionCore("0.1.0-SNAPSHOT")));
        assertEquals("[0, 2, 0]", java.util.Arrays.toString(AppInfoService.versionCore("0.2.0-beta.1")));
        assertEquals("[1, 10, 2]", java.util.Arrays.toString(AppInfoService.versionCore("v1.10.2")));
        assertEquals("[0, 1]", java.util.Arrays.toString(AppInfoService.versionCore("0.1")));
        assertEquals("[1, 0]", java.util.Arrays.toString(AppInfoService.versionCore("1.0b3")));
        assertNull(AppInfoService.versionCore("dev"));
        assertNull(AppInfoService.versionCore("main"));
        assertNull(AppInfoService.versionCore(null));
    }

    @Test
    void compareCoresFillsMissingPartsWithZero() {
        int[] current = AppInfoService.versionCore("0.1.0-SNAPSHOT");
        assertNotNull(current);
        // 远程更大 → 有更新
        assertTrue(AppInfoService.compareCores(AppInfoService.versionCore("0.2.0"), current) > 0);
        // 相等 → 已是最新（0.1.0-SNAPSHOT 对 0.1.0 不算落后）
        assertEquals(0, AppInfoService.compareCores(AppInfoService.versionCore("0.1.0"), current));
        assertEquals(0, AppInfoService.compareCores(AppInfoService.versionCore("0.1"), current));
        // 远程更小 → 本地是先行 beta
        assertTrue(AppInfoService.compareCores(AppInfoService.versionCore("0.0.9"), current) < 0);
        // 逐段数值比较（10 > 9，不是字典序）
        assertTrue(AppInfoService.compareCores(AppInfoService.versionCore("0.10.0"),
                AppInfoService.versionCore("0.9.0")) > 0);
    }

    @Test
    void normalizeTagStripsLeadingV() {
        assertEquals("0.1.0", AppInfoService.normalizeTag("v0.1.0"));
        assertEquals("1.2.3", AppInfoService.normalizeTag("V1.2.3"));
        assertEquals("0.1.0", AppInfoService.normalizeTag("0.1.0"));
        assertEquals("verify", AppInfoService.normalizeTag("verify"), "v 后非数字不动前缀");
    }

    @Test
    void newestTagPicksLargestVersionIgnoringNonVersions() {
        List<Map<String, Object>> tags = List.of(
                Map.of("name", "build-main"),
                Map.of("name", "v0.0.9"),
                Map.of("name", "v0.1.0"),
                Map.of("name", "nightly"));
        assertEquals("v0.1.0", AppInfoService.newestTag(tags));
        assertNull(AppInfoService.newestTag(List.of(Map.of("name", "no-version-here"))));
    }

    // ── 更新检查三态判定 ──

    @Test
    void remoteNewerThanCurrentReportsUpdateAvailable() {
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(200, release("v0.2.0")));
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", transport);
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("update_available", out.get("status"));
        assertEquals("0.2.0", out.get("latest_version"));
        assertEquals("0.1.0-SNAPSHOT", out.get("current_version"));
        assertEquals("v0.2.0", out.get("tag_name"));
        assertEquals("releases", out.get("source"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree/releases/tag/v0.2.0", out.get("release_url"));
    }

    @Test
    void remoteOlderThanCurrentReportsAheadBeta() {
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT",
                FakeTransport.of(FakeResponse.json(200, release("v0.0.9"))));
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("ahead_beta", out.get("status"));
        assertEquals("0.0.9", out.get("latest_version"));
    }

    @Test
    void remoteEqualToCurrentReportsUpToDate() {
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT",
                FakeTransport.of(FakeResponse.json(200, release("v0.1.0"))));
        assertEquals("up_to_date", service.checkUpdate(false).get("status"));
    }

    @Test
    void unparseableSidesReportUnknownNeverUpdateAvailable() {
        // 本地 dev 构建无法与远程比对：必须 unknown，不能误报"有更新"或"先行版"
        AppInfoService service = new AppInfoService("dev",
                FakeTransport.of(FakeResponse.json(200, release("v0.2.0"))));
        assertEquals("unknown", service.checkUpdate(false).get("status"));
        // 远程 tag 无数字核心同样 unknown
        AppInfoService service2 = new AppInfoService("0.1.0-SNAPSHOT",
                FakeTransport.of(FakeResponse.json(200, release("nightly"))));
        assertEquals("unknown", service2.checkUpdate(false).get("status"));
    }

    // ── 无 Release 的 tags 回退 ──

    @Test
    void releases404FallsBackToTags() {
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(404, "{\"message\":\"Not Found\"}"),
                FakeResponse.json(200, repoJson()),
                FakeResponse.json(200, "[{\"name\":\"v0.0.9\"},{\"name\":\"v0.1.0\"}]"));
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", transport);
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("up_to_date", out.get("status"));
        assertEquals("tags", out.get("source"));
        assertEquals("v0.1.0", out.get("tag_name"));
        assertEquals("0.1.0", out.get("latest_version"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree/releases/tag/v0.1.0", out.get("release_url"));
    }

    @Test
    void inaccessibleRepoReportsUnpublishedNotError() {
        // 私有/未公开仓库：releases 与 repo 根探测都是 404——当前构建就是先行者，不是错误
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(404, "{}"),
                FakeResponse.json(404, "{}"));
        AppInfoService service = new AppInfoService("0.2.0-alpha.1", transport);
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("unpublished", out.get("status"));
        assertNull(out.get("latest_version"), "没有远程版本可比");
    }

    @Test
    void publicRepoWithoutAnyReleaseOrTagAlsoReportsUnpublished() {
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(404, "{}"),
                FakeResponse.json(200, repoJson()),
                FakeResponse.json(200, "[]"));
        AppInfoService service = new AppInfoService("0.2.0-alpha.1", transport);
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("unpublished", out.get("status"));
    }

    @Test
    void networkFailureReturnsOkFalseData() {
        // 连不上 GitHub 是本机网络问题：提醒即可，与"未公开"（unpublished）严格分开
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT",
                FakeTransport.failing(new IOException("connection reset")));
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertEquals("unknown", out.get("status"));
        assertTrue(String.valueOf(out.get("error")).contains("无法连接 GitHub"));
    }

    // ── 网络失败降级为数据 ──

    @Test
    void networkFailureReturnsOkFalseData() {
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT",
                FakeTransport.failing(new IOException("connection reset")));
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertEquals("unknown", out.get("status"));
        assertTrue(String.valueOf(out.get("error")).contains("无法连接 GitHub"));
    }

    @Test
    void rateLimitedResponseGetsSpecificError() {
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", FakeTransport.of(
                FakeResponse.json(403, "{\"message\":\"API rate limit exceeded\"}", Map.of("X-RateLimit-Remaining", "0"))));
        Map<String, Object> out = service.checkUpdate(false);
        assertEquals(Boolean.FALSE, out.get("ok"));
        assertTrue(String.valueOf(out.get("error")).contains("配额"), String.valueOf(out.get("error")));
    }

    @Test
    void requestCarriesGithubRequiredHeaders() throws Exception {
        FakeTransport transport = FakeTransport.of(FakeResponse.json(200, release("v0.1.0")));
        new AppInfoService("0.1.0", transport).checkUpdate(false);
        assertEquals(1, transport.requests.size());
        HttpRequest req = transport.requests.get(0);
        assertEquals("application/vnd.github+json", req.headers().firstValue("Accept").orElse(""));
        assertFalse(req.headers().firstValue("User-Agent").orElse("").isBlank(),
                "GitHub API requires a User-Agent");
        assertEquals(URI.create("https://api.github.com/repos/Saktawdi/OpenWorktree/releases/latest"),
                req.uri());
    }

    // ── 缓存：保护 GitHub 匿名配额 ──

    @Test
    void cachedWithinTtlAndForceBypasses() {
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(200, release("v0.2.0")),
                FakeResponse.json(200, release("v0.2.0")));
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", transport);
        service.checkUpdate(false);
        service.checkUpdate(false); // TTL 内走缓存，不再出网
        assertEquals(1, transport.requests.size(), "cached result must not re-hit GitHub");
        service.checkUpdate(true);  // force 绕过缓存
        assertEquals(2, transport.requests.size());
    }

    @Test
    void failedCheckIsNotCached() {
        FakeTransport transport = FakeTransport.of(
                FakeResponse.json(500, "{}"),
                FakeResponse.json(200, release("v0.2.0")));
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", transport);
        service.checkUpdate(false); // 失败 → 不缓存
        Map<String, Object> retry = service.checkUpdate(false); // 重试真实出网
        assertEquals("update_available", retry.get("status"));
        assertEquals(2, transport.requests.size());
    }

    // ── /api/app/info 静态数据 ──

    @Test
    void infoJsonCarriesProductAndRepoCoordinates() {
        AppInfoService service = new AppInfoService("0.1.0-SNAPSHOT", FakeTransport.of());
        Map<String, Object> info = service.infoJson();
        assertEquals("OpenWorktree", info.get("name"));
        assertEquals("0.1.0-SNAPSHOT", info.get("version"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree", info.get("repo_url"));
        assertEquals("https://github.com/Saktawdi/OpenWorktree/releases/latest", info.get("download_url"));
    }

    @Test
    void resolveVersionNeverReturnsUnfilteredPlaceholder() {
        // 真实 classpath：mvn 构建下是过滤后的版本号；IDE 直跑回退 dev——两者都不能是占位符原文
        String v = AppInfoService.resolveVersion();
        assertNotNull(v);
        assertFalse(v.isBlank());
        assertFalse(v.contains("${"), "unfiltered placeholder must not leak as version: " + v);
    }

    // ── 假 GitHub 应答 ──

    private static String release(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"OpenWorktree " + tag + "\","
                + "\"html_url\":\"https://github.com/Saktawdi/OpenWorktree/releases/tag/" + tag + "\","
                + "\"published_at\":\"2026-01-01T00:00:00Z\",\"prerelease\":false,\"draft\":false}";
    }

    private static String repoJson() {
        return "{\"full_name\":\"Saktawdi/OpenWorktree\",\"private\":true}";
    }

    /** 可编程的假 GitHub：按序吐应答、记录收到的请求、可注入 IO 故障。 */
    private static final class FakeTransport implements AppInfoService.HttpTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        private final Queue<HttpResponse<String>> responses = new ArrayDeque<>();
        private final IOException failure;

        private FakeTransport(List<HttpResponse<String>> responses, IOException failure) {
            this.responses.addAll(responses);
            this.failure = failure;
        }

        static FakeTransport of(HttpResponse<String>... responses) {
            return new FakeTransport(List.of(responses), null);
        }

        static FakeTransport failing(IOException e) {
            return new FakeTransport(List.of(), e);
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            HttpResponse<String> next = responses.poll();
            if (next == null) {
                throw new IOException("no canned response for " + request.uri());
            }
            return next;
        }
    }

    /** 最小化的 HttpResponse<String> 桩。 */
    private record FakeResponse(int code, String body, Map<String, List<String>> headerMap)
            implements HttpResponse<String> {

        static HttpResponse<String> json(int code, String body) {
            return new FakeResponse(code, body, Map.of());
        }

        static HttpResponse<String> json(int code, String body, Map<String, String> headers) {
            Map<String, List<String>> h = new java.util.LinkedHashMap<>();
            headers.forEach((k, v) -> h.put(k, List.of(v)));
            return new FakeResponse(code, body, h);
        }

        @Override
        public int statusCode() {
            return code;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(headerMap, (a, b) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
