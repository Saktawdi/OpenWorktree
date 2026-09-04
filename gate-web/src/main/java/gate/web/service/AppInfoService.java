package gate.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用信息（设置中心「应用设置」页的数据源）：产品名、当前软件版本、GitHub 仓库坐标，
 * 以及"检查更新"——读取远程 GitHub 仓库最新发行版本并与本地版本比对。
 *
 * <p>当前软件版本在构建期由 Maven 资源过滤写进 {@code /app.properties}（父 pom 的
 * {@code project.version}，单一事实来源）；jar 部署时回退到 manifest 的
 * {@code Implementation-Version}；两者都拿不到（本地源码直跑）时返回 {@code dev}，
 * 更新检查按"无法比对"处理，绝不误报"有新版本"或"先行版"。
 *
 * <p>远程版本取 {@code GET /releases/latest}（最新一个非 draft 非 prerelease 的正式发行）；
 * 仓库从未发过正式 Release 时 404，退回 {@code /tags} 按版本号取最大 tag——先行开发期只有
 * tag 没有 Release 也能被检查到。比对规则取数字核心（{@code v0.2.0-beta.1 → 0.2.0}）：
 * 远程大于本地 → 有更新；远程小于本地 → 本地是先行 beta 构建；相等 → 已是最新。
 * GitHub 匿名配额只有 60 次/时/IP，成功结果在内存缓存 {@link #CHECK_TTL}，{@code force} 才强刷。
 *
 * <p>网络失败不抛异常：以 {@code ok:false + error} 作为应答数据返回，由前端渲染重试提示——
 * 检查更新是个探测动作，不该把探测失败升级成接口错误。
 */
public final class AppInfoService {

    public static final String PRODUCT_NAME = "OpenWorktree";
    public static final String REPO_OWNER = "Saktawdi";
    public static final String REPO_NAME = "OpenWorktree";
    public static final String REPO_URL = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME;
    public static final String RELEASES_URL = REPO_URL + "/releases";
    /** 有更新时前端"前往下载页"的跳转目标（在线更新上线前的过渡出口）。 */
    public static final String DOWNLOAD_URL = RELEASES_URL + "/latest";

    private static final String API_BASE = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME;
    /** 版本号的数字核心：从首个数字段起，连续的「数字.数字」序列（0.1.0-SNAPSHOT → 0.1.0）。 */
    private static final Pattern CORE_RE = Pattern.compile("\\d+(?:\\.\\d+)*");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** 成功检查结果的缓存时长：防切页/刷新把 GitHub 匿名配额（60 次/时/IP）打穿。 */
    private static final Duration CHECK_TTL = Duration.ofMinutes(5);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String version;
    private final HttpTransport transport;
    private volatile CachedCheck cached;

    /** HTTP 出口缝：测试注入假 GitHub 应答，不真的出网。 */
    @FunctionalInterface
    public interface HttpTransport {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private record CachedCheck(Map<String, Object> body, Instant at) {
    }

    public AppInfoService() {
        this(resolveVersion(), defaultTransport());
    }

    AppInfoService(String version, HttpTransport transport) {
        this.version = version == null || version.isBlank() ? "dev" : version.trim();
        this.transport = transport;
    }

    private static HttpTransport defaultTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return req -> http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** 当前软件版本：构建期过滤资源 → jar manifest → dev 兜底。 */
    static String resolveVersion() {
        try (InputStream in = AppInfoService.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("app.version");
                // 含 ${ 说明资源没经过 Maven 过滤（IDE 直拷），当没拿到处理
                if (v != null && !v.isBlank() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // 版本探测失败走后续回退，不该让应用信息接口挂掉
        }
        Package pkg = AppInfoService.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null
                && !pkg.getImplementationVersion().isBlank()) {
            return pkg.getImplementationVersion().trim();
        }
        return "dev";
    }

    /** {@code GET /api/app/info} 应答体。 */
    public Map<String, Object> infoJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", PRODUCT_NAME);
        m.put("version", version);
        m.put("repo_owner", REPO_OWNER);
        m.put("repo_name", REPO_NAME);
        m.put("repo_url", REPO_URL);
        m.put("releases_url", RELEASES_URL);
        m.put("download_url", DOWNLOAD_URL);
        return m;
    }

    /**
     * {@code GET /api/app/update-check} 应答体。{@code force=true}（前端手动点按钮）绕过缓存。
     * 字段：{@code ok / status(up_to_date|update_available|ahead_beta|unknown) / current_version /
     * latest_version / tag_name / release_url / published_at / source / error}。
     */
    public Map<String, Object> checkUpdate(boolean force) {
        CachedCheck entry = cached;
        if (!force && entry != null && Duration.between(entry.at(), Instant.now()).compareTo(CHECK_TTL) < 0) {
            return entry.body();
        }
        Map<String, Object> body = doCheck();
        if (Boolean.TRUE.equals(body.get("ok"))) {
            cached = new CachedCheck(body, Instant.now());
        }
        return body;
    }

    private Map<String, Object> doCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("status", "unknown");
        out.put("current_version", version);
        try {
            HttpResponse<String> resp = githubGet(API_BASE + "/releases/latest");
            int code = resp.statusCode();
            if (code == 200) {
                Map<String, Object> release = parseObject(resp.body());
                return verdict(out,
                        str(release.get("tag_name")),
                        str(release.get("html_url")),
                        str(release.get("published_at")),
                        "releases");
            }
            if (code == 404) {
                // 先探测仓库本身：私有/不存在的仓库对匿名 API 一律 404，报错文案必须分开
                HttpResponse<String> repoProbe = githubGet(API_BASE);
                if (repoProbe.statusCode() == 404) {
                    return error(out, "无法访问远程仓库 " + REPO_OWNER + "/" + REPO_NAME
                            + "（可能尚未公开、为私有仓库或地址有误）");
                }
                if (repoProbe.statusCode() != 200) {
                    return error(out, "GitHub API 返回 HTTP " + repoProbe.statusCode() + "（repo）");
                }
                // 仓库可达但没有正式 Release（先行开发期常见）：退回 tags 取最新
                HttpResponse<String> tags = githubGet(API_BASE + "/tags?per_page=100");
                if (tags.statusCode() == 200) {
                    String tag = newestTag(parseArray(tags.body()));
                    if (tag != null) {
                        return verdict(out, tag, tagPageUrl(tag), null, "tags");
                    }
                    return error(out, "远程仓库还没有任何发布版本（无 Release 也无 tag）");
                }
                return error(out, "GitHub API 返回 HTTP " + tags.statusCode() + "（tags）");
            }
            if (code == 403 && "0".equals(resp.headers().firstValue("X-RateLimit-Remaining").orElse(""))) {
                return error(out, "GitHub API 匿名配额已用完（60 次/时/IP），请稍后再试");
            }
            return error(out, "GitHub API 返回 HTTP " + code);
        } catch (IOException e) {
            return error(out, "无法连接 GitHub：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(out, "更新检查被中断");
        }
    }

    private Map<String, Object> verdict(Map<String, Object> out, String rawTag, String url,
                                        String publishedAt, String source) {
        if (rawTag == null || rawTag.isBlank()) {
            return error(out, "远程发行版本号缺失");
        }
        out.put("tag_name", rawTag);
        out.put("latest_version", normalizeTag(rawTag));
        out.put("release_url", url == null || url.isBlank() ? tagPageUrl(rawTag) : url);
        out.put("published_at", publishedAt);
        out.put("source", source);
        int[] remote = versionCore(normalizeTag(rawTag));
        int[] current = versionCore(version);
        out.put("ok", true);
        if (remote == null || current == null) {
            out.put("status", "unknown");
            return out;
        }
        int cmp = compareCores(remote, current);
        out.put("status", cmp > 0 ? "update_available" : cmp < 0 ? "ahead_beta" : "up_to_date");
        return out;
    }

    private static Map<String, Object> error(Map<String, Object> out, String message) {
        out.put("ok", false);
        out.put("status", "unknown");
        out.put("error", message);
        return out;
    }

    private HttpResponse<String> githubGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                // GitHub API 强制要求 User-Agent，缺失直接 403
                .header("User-Agent", PRODUCT_NAME + "-updater")
                .GET()
                .build();
        return transport.send(req);
    }

    /** 从 tags 数组里挑数字核心最大的一个（GitHub /tags 不按版本排序）。 */
    static String newestTag(List<Map<String, Object>> tags) {
        String best = null;
        int[] bestCore = null;
        for (Map<String, Object> tag : tags) {
            String name = str(tag.get("name"));
            int[] core = versionCore(normalizeTag(name));
            if (core == null) {
                continue;
            }
            if (bestCore == null || compareCores(core, bestCore) > 0) {
                bestCore = core;
                best = name;
            }
        }
        return best;
    }

    private static String tagPageUrl(String tag) {
        return REPO_URL + "/releases/tag/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8);
    }

    /** 去掉 v/V 前缀的展示版本号。 */
    static String normalizeTag(String tag) {
        String t = tag.trim();
        return t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1)) ? t.substring(1) : t;
    }

    /**
     * 版本号里的数字核心：{@code 0.1.0-SNAPSHOT / v0.2.0-beta.1 / 1.0b3} → {@code [0,1,0]} 等；
     * 后缀（-SNAPSHOT、-beta.1）不参与比较。完全没有数字（{@code dev}、{@code main}）返回 null，
     * 调用方按"无法比对"处理。
     */
    static int[] versionCore(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = CORE_RE.matcher(version);
        if (!m.find()) {
            return null;
        }
        String[] parts = m.group().split("\\.");
        int[] core = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            core[i] = Integer.parseInt(parts[i]);
        }
        return core;
    }

    /** 逐段数值比较，缺位补 0（0.2 == 0.2.0）。负数=remote 小于 current。 */
    static int compareCores(int[] remote, int[] current) {
        int len = Math.max(remote.length, current.length);
        for (int i = 0; i < len; i++) {
            int r = i < remote.length ? remote[i] : 0;
            int c = i < current.length ? current[i] : 0;
            if (r != c) {
                return Integer.compare(r, c);
            }
        }
        return 0;
    }

    private static List<Map<String, Object>> parseArray(String json) {
        try {
            return JsonParsing.array(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return JsonParsing.object(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** GitHub 应答的 JSON 解析（解析失败按空数据回退，检查流程自己产出错误信息）。 */
    private static final class JsonParsing {
        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> array(String json) throws Exception {
            Object o = JSON.readValue(json, Object.class);
            if (o instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            throw new IllegalArgumentException("expected JSON array");
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(String json) throws Exception {
            Object o = JSON.readValue(json, Object.class);
            if (o instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalArgumentException("expected JSON object");
        }
    }
}
