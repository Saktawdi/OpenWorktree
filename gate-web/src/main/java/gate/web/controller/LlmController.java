package gate.web.controller;

import gate.adapters.engine.ApiKeyResolver;
import gate.adapters.http.TrustAllTls;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;
import gate.ports.store.ProviderRepository;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller providing unified LLM Chat proxy endpoint: POST /api/llm/chat.
 * Resolves credentials via KMS from ProviderRepository so neither the UI nor plugins
 * hold plaintext secrets. Supports both SSE streaming (stream=true) and buffered JSON responses.
 */
public final class LlmController implements WebController {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final ProviderRepository providers;
    private final KmsService kms;
    private final HttpClient http;

    public LlmController(ProviderRepository providers, KmsService kms) {
        this(providers, kms, TrustAllTls.apply(HttpClient.newBuilder())
                .connectTimeout(CONNECT_TIMEOUT).build());
    }

    public LlmController(ProviderRepository providers, KmsService kms, HttpClient http) {
        this.providers = providers;
        this.kms = kms;
        this.http = http;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/llm/chat", this::chat);
    }

    public void chat(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String providerId = str(req, "provider_id");
        String model = str(req, "model");
        Object messagesObj = req.get("messages");
        if (!(messagesObj instanceof List<?> messages) || messages.isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "messages array is required and cannot be empty");
        }

        ProviderRepository.ProviderRow provider = resolveProvider(providerId);
        String resolvedModel = resolveModel(provider, model);
        String apiKey = ApiKeyResolver.resolve(provider.apiKeyRef(), kms);

        String baseUrl = provider.baseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/chat/completions";

        boolean stream = Boolean.TRUE.equals(req.get("stream"));
        Map<String, Object> upstreamPayload = new LinkedHashMap<>();
        upstreamPayload.put("model", resolvedModel);
        upstreamPayload.put("messages", messages);
        upstreamPayload.put("stream", stream);
        if (req.containsKey("temperature")) {
            upstreamPayload.put("temperature", req.get("temperature"));
        }
        if (req.containsKey("max_tokens")) {
            upstreamPayload.put("max_tokens", req.get("max_tokens"));
        }

        String requestBody = Json.write(upstreamPayload);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }
        if (stream) {
            reqBuilder.header("Accept", "text/event-stream");
        }

        if (stream) {
            forwardStreaming(ctx, reqBuilder.build(), url);
        } else {
            forwardBuffered(ctx, reqBuilder.build(), url);
        }
    }

    private void forwardBuffered(Context ctx, HttpRequest req, String url) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "upstream unreachable: " + url + " (" + e.getMessage() + ")", e);
        }

        ctx.status(resp.statusCode());
        ctx.contentType("application/json");
        ctx.result(resp.body());
    }

    private void forwardStreaming(Context ctx, HttpRequest req, String url) {
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "stream connect failed: " + url + " (" + e.getMessage() + ")", e);
        }

        if (resp.statusCode() / 100 != 2) {
            String errBody;
            try (InputStream in = resp.body()) {
                errBody = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
            } catch (Exception e) {
                errBody = "<unable to read error response>";
            }
            ctx.status(resp.statusCode());
            ctx.contentType("application/json");
            ctx.result(errBody);
            return;
        }

        ctx.res().setStatus(200);
        ctx.res().setCharacterEncoding("UTF-8");
        ctx.res().setContentType("text/event-stream");
        // 禁加 Connection: close：LLM 流式回包与 Session SSE 同一机理，close 会让 Jetty 立即关连接。
        ctx.res().addHeader("Cache-Control", "no-cache");
        ctx.res().addHeader("X-Accel-Buffering", "no");

        try (InputStream in = resp.body();
             OutputStream out = ctx.res().getOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception e) {
            // Client disconnect or stream termination
        }
    }

    private ProviderRepository.ProviderRow resolveProvider(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return providers.find(providerId.trim()).orElseThrow(() ->
                    new GateException(GateErrorCode.USAGE, "provider not found: " + providerId));
        }
        // Option A: choose the first configured provider, or first non-internal provider
        List<ProviderRepository.ProviderRow> all = providers.findAll();
        for (ProviderRepository.ProviderRow p : all) {
            if ("cli-default".equals(p.id()) || "manual".equals(p.id())) continue;
            if (ApiKeyResolver.isConfigured(p.apiKeyRef())) {
                return p;
            }
        }
        for (ProviderRepository.ProviderRow p : all) {
            if ("cli-default".equals(p.id()) || "manual".equals(p.id())) continue;
            return p;
        }
        throw new GateException(GateErrorCode.USAGE, "no available LLM provider configured");
    }

    private String resolveModel(ProviderRepository.ProviderRow provider, String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel.trim();
        }
        List<String> models = providers.models(provider.id());
        if (!models.isEmpty()) {
            return models.get(0);
        }
        throw new GateException(GateErrorCode.USAGE, "provider has no models configured: " + provider.id());
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }
}
