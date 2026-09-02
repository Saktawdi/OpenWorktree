package gate.web.service;

import gate.adapters.engine.ApiKeyResolver;
import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;
import gate.ports.store.ProviderRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls the model list from a provider's upstream {@code GET {base_url}/models} endpoint (V5 web
 * console — the settings page previously had to type model ids by hand).
 *
 * <p>Credential handling mirrors the review engine: the provider row's {@code api_key_ref} is
 * resolved through {@link ApiKeyResolver} — the {@code kms:} ciphertext written by the settings
 * center. {@code none} / {@code unconfigured} / blank means no credential and the call is attempted
 * unauthenticated. The plaintext key never reaches the DB, the response body, or a log line.
 *
 * <p>Response shapes accepted (OpenAI-compatible first, because ADR-9 routes through newapi):
 * {@code {"data":[{"id":"..."}]}}, Anthropic's {@code {"models":[{"id":"...""}]}}, a bare array of
 * objects with {@code id}, or a bare array of plain strings.
 */
public final class ProviderModelFetcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final KmsService kms;
    private final HttpClient http;

    public ProviderModelFetcher(KmsService kms) {
        this.kms = kms;
        // 上游可能是自签/代理证书链（PKIX 拒绝），本地工具放宽 TLS（见 TrustAllTls 的边界说明）。
        this.http = gate.adapters.http.TrustAllTls.apply(HttpClient.newBuilder())
                .connectTimeout(TIMEOUT).build();
    }

    /**
     * @return the upstream model ids, sorted and de-duplicated (never empty for a 2xx response —
     *         an empty list is reported as an upstream contract error instead of wiping the
     *         provider's model table).
     */
    public List<String> fetch(ProviderRepository.ProviderRow provider) {
        String url = provider.baseUrl().replaceAll("/+$", "") + "/models";
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
        String apiKey = resolveApiKey(provider.apiKeyRef());
        if (apiKey != null) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream unreachable: " + url + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "interrupted while fetching models from " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream " + url + " returned HTTP " + resp.statusCode());
        }
        List<String> models = parseModelIds(resp.body());
        if (models.isEmpty()) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "upstream " + url + " returned no recognizable model ids");
        }
        return models;
    }

    /** Resolves the provider's credential through the shared ApiKeyResolver; null = no key. */
    private String resolveApiKey(String apiKeyRef) {
        return ApiKeyResolver.resolve(apiKeyRef, kms);
    }

    /** Best-effort parse of the known upstream model-list shapes; empty list on no match. */
    public static List<String> parseModelIds(String body) {
        Object parsed;
        try {
            parsed = MiniJson.parse(body == null ? "" : body.trim());
        } catch (Exception e) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        collectIds(parsed instanceof Map<?, ?> m && m.containsKey("data") ? m.get("data")
                : parsed instanceof Map<?, ?> mm && mm.containsKey("models") ? mm.get("models")
                : parsed,
                ids);
        List<String> out = new ArrayList<>(ids);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectIds(Object node, Set<String> out) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectIds(item, out);
            }
        } else if (node instanceof Map<?, ?> m) {
            Object id = m.get("id");
            if (id != null && !id.toString().isBlank()) {
                out.add(id.toString());
            }
        } else if (node instanceof String s && !s.isBlank()) {
            out.add(s);
        }
    }
}
