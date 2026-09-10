package gate.web.service;

import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Upstream probes for OpenCode provider entries: pull the model
 * list from {@code GET {baseURL}/models} and run a connectivity test through
 * {@code POST {baseURL}/chat/completions}. Both take the explicit base_url/api_key from the edit
 * dialog so a provider can be verified before it is ever written to opencode.json.
 *
 * <p>Same trust posture as {@link ProviderModelFetcher}: loopback tool, user-supplied upstream,
 * relaxed TLS for self-signed proxy chains. Failure is returned as data ({@code ok:false} + error)
 * for the test probe — the caller renders it, it is not an endpoint error.
 */
public final class OpenCodeModelsApi {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    /** Minimal prompt for the connectivity probe — one completion token is enough to prove the path. */
    private static final String DEFAULT_TEST_PROMPT = "ping";

    private final HttpClient http;

    public OpenCodeModelsApi() {
        this.http = gate.adapters.http.TrustAllTls.apply(HttpClient.newBuilder())
                .connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Fetches the upstream model ids; throws USAGE/GATE_ERROR_IO on a bad or unreachable upstream. */
    public List<String> fetchModels(String baseUrl, String apiKey) {
        String url = requireBaseUrl(baseUrl) + "/models";
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<String> resp = send(req.build(), url);
        if (resp.statusCode() / 100 != 2) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream " + url + " returned HTTP " + resp.statusCode());
        }
        List<String> models = ProviderModelFetcher.parseModelIds(resp.body());
        if (models.isEmpty()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream " + url + " returned no recognizable model ids");
        }
        return models;
    }

    /**
     * Runs one tiny chat completion against the provider and reports the outcome as data:
     * {@code {ok, status_code, latency_ms, reply?|error?}}. The reply is truncated; the API key
     * never leaves this process except as the Authorization header of the probe itself.
     */
    public Map<String, Object> testModel(String baseUrl, String apiKey, String model, String prompt) {
        String url = requireBaseUrl(baseUrl) + "/chat/completions";
        String body = gate.web.util.Json.write(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user",
                        "content", prompt == null || prompt.isBlank() ? DEFAULT_TEST_PROMPT : prompt.trim())),
                "max_tokens", 16));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey.trim());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        long started = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = send(req.build(), url);
        } catch (GateException e) {
            out.put("ok", false);
            out.put("status_code", 0);
            out.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
            out.put("error", e.getMessage());
            return out;
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        out.put("status_code", resp.statusCode());
        out.put("latency_ms", latencyMs);
        if (resp.statusCode() / 100 == 2) {
            out.put("ok", true);
            out.put("reply", extractReply(resp.body()));
        } else {
            out.put("ok", false);
            out.put("error", "HTTP " + resp.statusCode() + ": " + tail(resp.body()));
        }
        return out;
    }

    private HttpResponse<String> send(HttpRequest req, String url) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream unreachable: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "interrupted while calling " + url, e);
        }
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "base_url is required");
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new GateException(GateErrorCode.USAGE, "base_url must start with http:// or https://");
        }
        return trimmed;
    }

    /** Best-effort OpenAI-compatible reply extraction; never fails the probe. */
    private static String extractReply(String body) {
        try {
            Object parsed = MiniJson.parse(body == null ? "" : body.trim());
            if (parsed instanceof Map<?, ?> root
                    && root.get("choices") instanceof List<?> choices && !choices.isEmpty()
                    && choices.get(0) instanceof Map<?, ?> choice
                    && choice.get("message") instanceof Map<?, ?> message) {
                Object content = message.get("content");
                if (content != null && !content.toString().isBlank()) {
                    return truncate(content.toString().trim(), 200);
                }
            }
        } catch (Exception ignored) {
            // A 2xx with an unparseable body still proves connectivity.
        }
        return "";
    }

    private static String tail(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        return truncate(body.trim(), 300);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
