package gate.web.service;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.util.Json;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 线上模型目录 + 「智能匹配」：把一个模型 id 解析成 opencode 模型配置
 * （{@code limit} / {@code modalities} / {@code reasoning} / {@code tool_call} / …）。
 *
 * <p>数据源是 {@code https://models.dev/api.json}——opencode 生态自己用的那份目录（opencode 的
 * 模型元数据同样取自这里），所以「线上最新配置项」的语义与本机配置的语义天然对齐：匹配结果可以直接
 * 写进 opencode.json 的模型节点，不需要二次翻译。目录带 TTL 缓存（{@value #TTL_MINUTES} 分钟），
 * 拉取失败时退回上一次的缓存而不是让匹配整体失败。
 *
 * <p><strong>为什么要匹配而不是查表</strong>：本机配的供应商大量是中转/自建网关
 * （newapi、one-api 之类），它们暴露的模型 id 很少与上游厂商逐字相同——常见变体是前缀
 * （{@code moonshotai/kimi-k3}）、后缀（{@code -api} / {@code -free} / {@code -preview} /
 * {@code -0731} 日期戳）与大小写/点号差异（{@code GLM-5.3-Flash} vs {@code glm-5.3-flash}）。
 * 因此匹配走「归一化 + 逐级降级」的天梯：先做大小写/点号/下划线归一，再按前缀→后缀→日期戳的顺序
 * 逐级剥离，取第一个命中的档位。宁可少匹配，不可匹配错——所以只做可解释的形态归一，不做模糊打分。
 *
 * <p><strong>取哪一家的行</strong>：同一个模型 id 在目录里往往有上百家供应商都有（中转商都收录同
 * 一批模型），彼此给的上下文长度/模态还不完全一样。选行是两趟：先按 baseURL 主机名收窄到「这个端点
 * 到底是哪一家」（{@code api.deepseek.com} → 只在该家的行里找），找到就用它——这是最懂这个端点的
 * 一行；收不窄（自建网关、未知中转域名）或那一趟全落空，再退到全库找。同一档位内先排除
 * {@code status: deprecated} 的行，再按 {@link #AUTHORITY} 的第一方厂商优先，最后按供应商 id
 * 字典序，保证「同一份目录 + 同一个输入」永远给同一行。
 */
public final class ModelCatalogService {

    /** 目录地址覆盖（系统属性，测试用来指向本地 mock，不碰真实网络）。 */
    public static final String CATALOG_URL_PROPERTY = "opencode.models.catalog.url";

    private static final String DEFAULT_CATALOG_URL = "https://models.dev/api.json";
    private static final long TTL_MINUTES = 10;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** 目录体量兜底：正常约 5MB，防止上游异动把内存打满。 */
    private static final int MAX_BODY_CHARS = 48 * 1024 * 1024;
    /** 天梯最多剥离几级，避免把一个正常 id 越剥越短剥成无关模型。 */
    private static final int MAX_LADDER_RUNGS = 4;

    /**
     * 可剥离的语义后缀，按「先长后短」排列。这些后缀不改变模型本体，只是同一模型在本地网关上的
     * 不同挂载名：{@code -free} 免费通道、{@code -api} 走 API 计费、{@code -preview} 预览版…
     */
    private static final List<String> STRIPPABLE_SUFFIXES = List.of(
            "-non-thinking", "-thinking", "-experimental", "-preview", "-instruct", "-latest",
            "-beta", "-alpha", "-free", "-api", "-exp", "-chat", "-hf");

    /** MMDD 日期戳（{@code -0731}）。 */
    private static final Pattern MMDD = Pattern.compile("-((?:0[1-9]|1[0-2])(?:[0-2][0-9]|3[01]))$");
    /** YYYYMMDD 日期戳（{@code -20250923}）。 */
    private static final Pattern YYYYMMDD = Pattern.compile("-(20\\d{6})$");

    /**
     * 第一方厂商优先序：id 撞名、又都没有「模型家族归属」加成时，取列表靠前的自家目录行。
     * 未列出的供应商排最后、按 id 字典序。
     */
    private static final List<String> AUTHORITY = List.of(
            "anthropic", "openai", "google", "google-vertex", "azure", "amazon-bedrock",
            "deepseek", "moonshotai", "moonshotai-cn", "zhipuai", "zai", "alibaba", "alibaba-cn",
            "minimax", "stepfun", "stepfun-ai", "xai", "mistral", "meta", "cohere",
            "opencode", "opencode-go", "github-copilot",
            "kimi-code-plan-global", "kimi-code-plan-cn");

    /**
     * 模型家族 → 该家族「自家供应商」的 id 子串。同一个模型在目录里往往几十家都有，
     * 只有模型 ID 前缀与供应商对得上时，那一家给的才是这个模型自身的规格。
     *
     * <p>没有这份归属表时，静态的 {@link #AUTHORITY} 顺序会让云厂商赢过模型原厂——例如
     * {@code deepseek-v4-flash} 会落到 azure 那一行（那行只写 text，视觉标签就丢了），而
     * deepseek 自家的行写着完整的 image 输入。子串匹配同时覆盖了同一家的分区/套餐版本
     * （{@code alibaba-cn}、{@code alibaba-token-plan}、{@code zai-coding-plan} …）。
     */
    private static final Map<String, List<String>> FAMILY_VENDORS = Map.ofEntries(
            Map.entry("deepseek", List.of("deepseek")),
            Map.entry("glm", List.of("zhipu", "zai")),
            Map.entry("kimi", List.of("moonshot", "kimi")),
            Map.entry("moonshot", List.of("moonshot", "kimi")),
            Map.entry("qwen", List.of("alibaba", "qwen")),
            Map.entry("claude", List.of("anthropic")),
            Map.entry("gpt", List.of("openai")),
            Map.entry("codex", List.of("openai")),
            Map.entry("gemini", List.of("google")),
            Map.entry("grok", List.of("xai")),
            Map.entry("muse", List.of("meta")),
            Map.entry("llama", List.of("meta")),
            Map.entry("mistral", List.of("mistral")),
            Map.entry("mixtral", List.of("mistral")),
            Map.entry("minimax", List.of("minimax")),
            Map.entry("step", List.of("stepfun")),
            Map.entry("command", List.of("cohere")));

    private final HttpClient http;
    private final Object fetchLock = new Object();
    private volatile Catalog cached;

    public ModelCatalogService() {
        this.http = gate.adapters.http.TrustAllTls.apply(HttpClient.newBuilder())
                .connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 把一批模型 id 匹配成 opencode 模型配置。返回体面向前端直接合并进 {@code models}：
     * <pre>
     * {catalog_ok, catalog_url, provider_hint,
     *  matched: {"&lt;本地 id&gt;": {matched_id, provider, config}}, unmatched: ["…"]}
     * </pre>
     * 目录不可用（且无缓存）时给 {@code catalog_ok:false} + {@code catalog_error} 而不抛错——
     * 智能匹配是锦上添花，拉不到线上目录不该挡着用户手填。
     *
     * @param baseUrl  供应商端点，用来把匹配范围收窄到目录里对应的一家；可空
     * @param modelIds 要匹配的本地模型 id
     */
    public Map<String, Object> match(String baseUrl, List<String> modelIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog_url", catalogUrl());
        Catalog cat;
        try {
            cat = catalog(false);
        } catch (RuntimeException e) {
            out.put("catalog_ok", false);
            out.put("catalog_error", e.getMessage() == null ? e.toString() : e.getMessage());
            out.put("provider_hint", null);
            out.put("matched", Map.of());
            out.put("unmatched", modelIds == null ? List.of() : List.copyOf(modelIds));
            return out;
        }
        Set<String> scope = scopeFor(cat, baseUrl);
        Map<String, Object> matched = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : modelIds == null ? List.<String>of() : modelIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            if (!seen.add(id)) {
                continue;
            }
            Entry hit = bestHit(cat, scope, id);
            if (hit == null) {
                unmatched.add(id);
            } else {
                matched.put(id, renderMatch(hit));
            }
        }
        out.put("catalog_ok", true);
        out.put("provider_hint", scope.isEmpty() ? null : String.join(", ", new TreeSet<>(scope)));
        out.put("matched", matched);
        out.put("unmatched", unmatched);
        return out;
    }

    private static Map<String, Object> renderMatch(Entry hit) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("matched_id", hit.modelId());
        one.put("provider", hit.provider());
        one.put("config", hit.toOpenCodeConfig());
        return one;
    }

    // ────────────────────────── 范围与选行 ──────────────────────────

    /**
     * 匹配范围：baseURL 主机名能定位到目录里的供应商就返回那几家（同主机的兄弟目录行一并纳入，
     * 如 {@code opencode} / {@code opencode-go} 共用 {@code opencode.ai}）；定位不到返回空集，
     * 表示「不限范围、全库找」。
     */
    private static Set<String> scopeFor(Catalog cat, String baseUrl) {
        String host = hostOf(baseUrl);
        if (host == null) {
            return Set.of();
        }
        List<String> providers = cat.providersByHost().get(host);
        return providers == null ? Set.of() : new LinkedHashSet<>(providers);
    }

    /**
     * 逐级上天梯，取第一档命中的行。
     *
     * <p>范围一旦认出（baseURL 主机名对上目录里的一家）就<strong>只在那家找</strong>：既然知道这个
     * 端点属于谁，拿别家的行来填就是猜——{@code glm-5.2-old} 挂在 deepseek 端点上，说明它本就不该
     * 被匹配，而不是该去借 zhipuai 的行。反过来，主机名认不出（自建网关、未知中转域名）就没有可收窄
     * 的依据，这时才全库找——这正是「中转网关挂什么名字都能匹配上」的来源。
     */
    private static Entry bestHit(Catalog cat, Set<String> scope, String modelId) {
        for (String key : ladderKeys(modelId)) {
            List<Entry> hits = cat.byId().get(key);
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            Entry hit = pick(hits, scope, key);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 在允许的供应商集合里选最优那一行；scope 为空表示不限。 */
    private static Entry pick(List<Entry> hits, Set<String> scope, String key) {
        List<Entry> candidates = new ArrayList<>();
        for (Entry e : hits) {
            if (scope.isEmpty() || scope.contains(e.provider())) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(entryOrder(key));
        return candidates.get(0);
    }

    /**
     * 生成候选键：从「原始归一化」到「逐级剥离后缀/日期戳」，越靠前越精确。
     * 命名空间前缀（{@code org/}、{@code srv_xx:}）在最外层先剥，因为它与模型本体无关。
     */
    static List<String> ladderKeys(String modelId) {
        List<String> keys = new ArrayList<>();
        String bare = normalize(bare(modelId));
        addLadder(keys, bare);
        String full = normalize(modelId);
        if (!full.equals(bare)) {
            addLadder(keys, full);
        }
        return keys;
    }

    private static void addLadder(List<String> keys, String base) {
        String current = base;
        for (int i = 0; i < MAX_LADDER_RUNGS && !current.isEmpty(); i++) {
            if (!keys.contains(current)) {
                keys.add(current);
            }
            String stripped = stripOne(current);
            if (stripped == null || stripped.equals(current)) {
                break;
            }
            current = stripped;
        }
    }

    /** 剥离一级后缀或日期戳；无可剥离返回 null。 */
    private static String stripOne(String id) {
        for (String suffix : STRIPPABLE_SUFFIXES) {
            if (id.endsWith(suffix) && id.length() > suffix.length()) {
                return id.substring(0, id.length() - suffix.length());
            }
        }
        String mmdd = MMDD.matcher(id).replaceFirst("");
        if (!mmdd.equals(id)) {
            return mmdd;
        }
        String yyyymmdd = YYYYMMDD.matcher(id).replaceFirst("");
        return yyyymmdd.equals(id) ? null : yyyymmdd;
    }

    /** 剥掉命名空间前缀：{@code srv_xx:moonshotai/kimi-k3} → {@code kimi-k3}。 */
    private static String bare(String raw) {
        String s = raw.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s;
    }

    /** 大小写 / 点号 / 下划线 / 空格 归一，并折叠连续连字符。 */
    static String normalize(String raw) {
        String lowered = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        boolean lastDash = false;
        for (int i = 0; i < lowered.length(); i++) {
            char ch = lowered.charAt(i);
            char c = (ch == '.' || ch == '_' || ch == ' ' || ch == ':') ? '-' : ch;
            if (c == '-') {
                if (lastDash) {
                    continue;
                }
                lastDash = true;
            } else {
                lastDash = false;
            }
            sb.append(c);
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '-') {
            end--;
        }
        return sb.substring(0, end);
    }

    /**
     * 同档位内的选行顺序：模型家族的原厂优先 → 非弃用优先 → 第一方厂商优先 →
     * 供应商 id 字典序（保证「同一份目录 + 同一个输入」结果确定）。
     *
     * <p>原厂排在弃用之前是刻意的：{@code status: deprecated} 说的是<strong>这家不再提供</strong>，
     * 而我们要取的是模型自身的能力（上下文长度、模态），这跟还提不提供它无关。目录里
     * deepseek 自家的 {@code deepseek-v4-flash} 就标了 deprecated，若让弃用优先就会落到 azure 那行
     * ——azure 把输入写成纯 text，视觉能力就这么丢了；而模型本身明明是带 image 的。
     */
    private static Comparator<Entry> entryOrder(String key) {
        return Comparator
                .comparingInt((Entry e) -> familyRank(key, e.provider()))
                .thenComparing(Entry::deprecated)
                .thenComparingInt(e -> authorityRank(e.provider()))
                .thenComparing(Entry::provider);
    }

    /** 供应商是不是这个模型家族的原厂：0=是，1=不是。 */
    private static int familyRank(String modelKey, String provider) {
        for (Map.Entry<String, List<String>> family : FAMILY_VENDORS.entrySet()) {
            if (!modelKey.startsWith(family.getKey())) {
                continue;
            }
            for (String vendor : family.getValue()) {
                if (provider.contains(vendor)) {
                    return 0;
                }
            }
            // 家族认出来了但这家不是原厂：仍然按后面的第一方序排，不再看别的家族。
            return 1;
        }
        return 1;
    }

    private static int authorityRank(String provider) {
        int i = AUTHORITY.indexOf(provider);
        return i < 0 ? AUTHORITY.size() : i;
    }

    // ────────────────────────── 目录拉取与解析 ──────────────────────────

    /**
     * 取目录：命中 TTL 直接返回；否则拉一次。拉取失败且已有缓存时返回旧目录（stale），
     * 只有「从来没成功过 + 这次也失败」才把错误抛给调用方。
     */
    private Catalog catalog(boolean forceRefresh) {
        Catalog current = cached;
        if (!forceRefresh && current != null && !current.expired()) {
            return current;
        }
        synchronized (fetchLock) {
            current = cached;
            if (!forceRefresh && current != null && !current.expired()) {
                return current;
            }
            try {
                Catalog fresh = parse(catalogUrl(), fetch(catalogUrl()));
                cached = fresh;
                return fresh;
            } catch (RuntimeException e) {
                if (current != null) {
                    return current;
                }
                throw e;
            }
        }
    }

    private static String catalogUrl() {
        String override = System.getProperty(CATALOG_URL_PROPERTY);
        return override == null || override.isBlank() ? DEFAULT_CATALOG_URL : override.trim();
    }

    private String fetch(String url) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(new URI(url))
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (URISyntaxException e) {
            throw new GateException(GateErrorCode.USAGE, "invalid model catalog url: " + url, e);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog unreachable: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "interrupted while fetching model catalog " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog " + url + " returned HTTP " + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "model catalog " + url + " returned an empty body");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog body too large: " + body.length() + " chars");
        }
        return body;
    }

    /** 把目录 JSON 压成「归一化 id → 目录行」与「主机 → 供应商」两张紧凑索引，原文即丢。 */
    @SuppressWarnings("unchecked")
    private static Catalog parse(String url, String body) {
        Object parsed;
        try {
            parsed = Json.mapper().readValue(body, Object.class);
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog is not valid JSON: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "model catalog root is not an object");
        }
        Map<String, List<Entry>> byId = new HashMap<>();
        Map<String, List<String>> byHost = new HashMap<>();
        for (Map.Entry<?, ?> top : root.entrySet()) {
            if (!(top.getValue() instanceof Map<?, ?> rawProvider)) {
                continue;
            }
            Map<String, Object> provider = (Map<String, Object>) rawProvider;
            String providerId = String.valueOf(top.getKey());
            String host = hostOf(str(provider.get("api")));
            if (host != null) {
                byHost.computeIfAbsent(host, k -> new ArrayList<>()).add(providerId);
            }
            if (!(provider.get("models") instanceof Map<?, ?> modelMap)) {
                continue;
            }
            for (Map.Entry<?, ?> m : modelMap.entrySet()) {
                if (!(m.getValue() instanceof Map<?, ?> rawModel)) {
                    continue;
                }
                Entry entry = Entry.of(providerId, String.valueOf(m.getKey()), (Map<String, Object>) rawModel);
                byId.computeIfAbsent(normalize(entry.modelId()), k -> new ArrayList<>()).add(entry);
                // 少数行自带 id 且与 map key 不同：两个键都建索引，只在真的不同时多建一份。
                String declared = str(((Map<String, Object>) rawModel).get("id"));
                if (declared != null && !declared.isBlank()
                        && !normalize(declared).equals(normalize(entry.modelId()))) {
                    byId.computeIfAbsent(normalize(declared), k -> new ArrayList<>()).add(entry);
                }
            }
        }
        return new Catalog(url, System.currentTimeMillis(), byId, byHost);
    }

    /** 端点主机名（小写）；无法解析返回 null。 */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    /** 目录里一家供应商的一行模型；只留匹配与配置生成真正用到的字段。 */
    private record Entry(
            String provider,
            String modelId,
            String name,
            Long context,
            Long output,
            List<String> inputModalities,
            List<String> outputModalities,
            boolean reasoning,
            boolean toolCall,
            boolean temperature,
            boolean attachment,
            boolean deprecated) {

        static Entry of(String provider, String mapKey, Map<String, Object> model) {
            Map<String, Object> limit = model.get("limit") instanceof Map<?, ?> l
                    ? cast(l) : Map.of();
            Map<String, Object> modalities = model.get("modalities") instanceof Map<?, ?> mm
                    ? cast(mm) : Map.of();
            String declared = str(model.get("id"));
            return new Entry(
                    provider,
                    declared == null || declared.isBlank() ? mapKey : declared,
                    str(model.get("name")),
                    asLong(limit.get("context")),
                    asLong(limit.get("output")),
                    asStringList(modalities.get("input")),
                    asStringList(modalities.get("output")),
                    Boolean.TRUE.equals(model.get("reasoning")),
                    Boolean.TRUE.equals(model.get("tool_call")),
                    Boolean.TRUE.equals(model.get("temperature")),
                    Boolean.TRUE.equals(model.get("attachment")),
                    "deprecated".equalsIgnoreCase(str(model.get("status"))));
        }

        /**
         * 转成 opencode.json 模型节点的形状。四个布尔能力位（attachment/reasoning/tool_call/
         * temperature）一律显式写出——opencode 读的是布尔本身，缺键与 false 语义不同，写全了
         * 才不会让富能力模型退回「不支持」的默认。
         */
        Map<String, Object> toOpenCodeConfig() {
            Map<String, Object> cfg = new LinkedHashMap<>();
            if (name != null && !name.isBlank()) {
                cfg.put("name", name);
            }
            // limit 要么不写、要么上下文与输出齐全：opencode 见到缺一个的 limit 会拒绝整份
            // 配置（serve 起不来、所有会话失效），所以宁可整块不写，交给 opencode 的默认。
            if (context != null && context > 0 && output != null && output > 0) {
                Map<String, Object> limits = new LinkedHashMap<>();
                limits.put("context", context);
                limits.put("output", output);
                cfg.put("limit", limits);
            }
            Map<String, Object> modals = new LinkedHashMap<>();
            if (!inputModalities.isEmpty()) {
                modals.put("input", inputModalities);
            }
            if (!outputModalities.isEmpty()) {
                modals.put("output", outputModalities);
            }
            if (!modals.isEmpty()) {
                cfg.put("modalities", modals);
            }
            cfg.put("attachment", attachment);
            cfg.put("reasoning", reasoning);
            cfg.put("tool_call", toolCall);
            cfg.put("temperature", temperature);
            return cfg;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
    }

    /** 解析好的目录快照。 */
    private record Catalog(String url, long fetchedAtMillis,
                           Map<String, List<Entry>> byId,
                           Map<String, List<String>> providersByHost) {

        boolean expired() {
            return System.currentTimeMillis() - fetchedAtMillis > Duration.ofMinutes(TTL_MINUTES).toMillis();
        }
    }
}
