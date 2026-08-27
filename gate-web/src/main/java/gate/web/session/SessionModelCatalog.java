package gate.web.session;

import gate.application.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live model catalog for one session, proxied from its opencode serve instance
 * ({@code GET /config/providers}) — the same source OpenChamber's composer model picker uses.
 *
 * <p>The response is reduced to the fields the picker renders:
 * {@code {"providers":[{"id","name","models":[{"id","name","variants":[key,...],
 * "limit":{"context":N,"output":M}?}]}]}}.
 * {@code variants} keys are OpenCode reasoning-effort selections ("high"/"medium"/"low"/…)
 * sent back verbatim as {@code variant} on {@code prompt_async}.
 * {@code limit} carries the model's context/output token windows when exposed
 * (consumed by the session context-usage ring); it is omitted when unknown.
 */
public final class SessionModelCatalog {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * @param port the session's opencode serve port (&gt;0), or -1 when the CLI has no server
     */
    Map<String, Object> fetch(int port) {
        if (port <= 0) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("providers", List.of());
            empty.put("source", "no-server");
            empty.put("note", "当前会话的 CLI 没有可查询的服务端模型目录");
            return empty;
        }
        String url = "http://127.0.0.1:" + port + "/config/providers";
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "interrupted while fetching the model catalog from " + url, e);
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog unreachable (opencode serve on port " + port + "): " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "model catalog request failed: HTTP " + resp.statusCode());
        }
        return reduce(resp.body());
    }

    /** Maps the raw providers payload onto the lean picker shape; never throws on odd shapes. */
    public static Map<String, Object> reduce(String rawBody) {
        List<Map<String, Object>> providersOut = new ArrayList<>();
        Object parsed;
        try {
            parsed = MiniJson.parse(rawBody == null ? "" : rawBody.trim());
        } catch (Exception e) {
            Map<String, Object> malformed = new LinkedHashMap<>();
            malformed.put("providers", providersOut);
            malformed.put("source", "unparseable");
            return malformed;
        }
        Object rawProviders = parsed instanceof Map<?, ?> m ? m.get("providers") : null;
        if (rawProviders instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> p)) {
                    continue;
                }
                String pid = str(p.get("id"));
                if (pid == null || pid.isBlank()) {
                    continue;
                }
                Map<String, Object> provider = new LinkedHashMap<>();
                provider.put("id", pid);
                provider.put("name", str(p.get("name")) == null ? pid : str(p.get("name")));
                List<Map<String, Object>> modelsOut = new ArrayList<>();
                if (p.get("models") instanceof Map<?, ?> models) {
                    for (Map.Entry<?, ?> e : models.entrySet()) {
                        if (!(e.getValue() instanceof Map<?, ?> mm)) {
                            continue;
                        }
                        String mid = str(mm.get("id"));
                        if (mid == null || mid.isBlank()) {
                            mid = String.valueOf(e.getKey());
                        }
                        Map<String, Object> model = new LinkedHashMap<>();
                        model.put("id", mid);
                        model.put("name", str(mm.get("name")) == null ? mid : str(mm.get("name")));
                        model.put("variants", variantKeys(mm.get("variants")));
                        Map<String, Object> limit = limitTokens(mm.get("limit"));
                        if (limit != null) {
                            model.put("limit", limit);
                        }
                        modelsOut.add(model);
                    }
                }
                provider.put("models", modelsOut);
                providersOut.add(provider);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providersOut);
        out.put("source", "serve");
        return out;
    }

    /** Sorted variant keys of one model; empty list when the model exposes none. */
    private static List<String> variantKeys(Object variants) {
        List<String> keys = new ArrayList<>();
        if (variants instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                String key = str(k);
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
        }
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    /**
     * Extracts {@code {context, output}} token limits from one model's {@code limit} object
     * (OpenCode 1.x shape). Returns null when the model exposes no usable limits; individual
     * missing keys are omitted so the UI can fall back per-field.
     */
    private static Map<String, Object> limitTokens(Object limit) {
        if (!(limit instanceof Map<?, ?> m)) {
            return null;
        }
        Long context = positiveTokenCount(m.get("context"));
        Long output = positiveTokenCount(m.get("output"));
        if (context == null && output == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (context != null) {
            out.put("context", context);
        }
        if (output != null) {
            out.put("output", output);
        }
        return out;
    }

    private static Long positiveTokenCount(Object v) {
        if (v instanceof Number n) {
            long value = n.longValue();
            return value > 0 ? value : null;
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
