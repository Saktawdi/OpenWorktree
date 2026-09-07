package gate.web.controller;

import gate.adapters.engine.ApiKeyResolver;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.Clock;
import gate.ports.infra.KmsService;
import gate.ports.store.ProviderRepository;
import gate.web.service.ProviderModelFetcher;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider management controller.
 * Owns /api/providers/* routes.
 *
 * <p>Credentials (设置中心 LLM 密钥): the plaintext key arrives only on
 * {@code PUT /api/providers/{id}/credential}, is encrypted through the KMS port, and is persisted
 * as {@code kms:<ciphertext>} in {@code api_key_ref}. It never appears in list/detail responses,
 * logs, or argv.
 */
public final class ProviderController implements WebController {

    private final ProviderRepository providers;
    private final ProviderModelFetcher modelFetcher;
    private final KmsService kms;
    private final Clock clock;

    public ProviderController(ProviderRepository providers, ProviderModelFetcher modelFetcher, Clock clock) {
        this(providers, modelFetcher, null, clock);
    }

    public ProviderController(ProviderRepository providers, ProviderModelFetcher modelFetcher,
                              KmsService kms, Clock clock) {
        this.providers = providers;
        this.modelFetcher = modelFetcher;
        this.kms = kms;
        this.clock = clock;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/providers", this::list);
        app.post("/api/providers", this::create);
        app.get("/api/providers/{id}", this::detail);
        app.put("/api/providers/{id}", this::update);
        app.delete("/api/providers/{id}", this::delete);
        app.put("/api/providers/{id}/models", this::updateModels);
        app.post("/api/providers/{id}/models/fetch", this::fetchModels);
        app.post("/api/providers/{id}/models/probe", this::probeModels);
        app.put("/api/providers/{id}/credential", this::setCredential);
        app.delete("/api/providers/{id}/credential", this::clearCredential);
    }

    public void list(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProviderRepository.ProviderRow p : providers.findAll()) {
            if (p.id().equals("cli-default")) {
                continue;
            }
            List<String> models = providers.models(p.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("base_url", p.baseUrl());
            m.put("type", p.type());
            m.put("credential_configured", ApiKeyResolver.isConfigured(p.apiKeyRef()));
            m.put("model_count", models.size());
            m.put("models", models);
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providers", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void create(Context ctx) {
        Map<String, Object> req = Json.parseObject(ctx.body());
        String id = required(req, "id");
        if (providers.find(id).isPresent()) {
            throw new GateException(GateErrorCode.USAGE, "provider already exists: " + id);
        }
        ProviderRepository.ProviderRow row = parseProvider(req, id, null);
        providers.upsert(row, clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    public void detail(Context ctx) {
        String id = ctx.pathParam("id");
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ProviderRepository.ProviderRow existing = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        ProviderRepository.ProviderRow row = parseProvider(Json.parseObject(ctx.body()), id, existing);
        providers.upsert(row, clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (providers.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        }
        if ("manual".equals(id) || "cli-default".equals(id)) {
            throw new GateException(GateErrorCode.USAGE, "manual provider cannot be deleted");
        }
        providers.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void updateModels(Context ctx) {
        String id = ctx.pathParam("id");
        if (providers.find(id).isEmpty()) {
            throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        }
        Map<String, Object> req = Json.parseObject(ctx.body());
        Object rawModels = req.get("models");
        if (!(rawModels instanceof List<?> list)) {
            throw new GateException(GateErrorCode.USAGE, "models must be an array");
        }
        List<String> models = new ArrayList<>();
        for (Object value : list) {
            String model = value == null ? "" : value.toString().trim();
            if (!model.isBlank() && !models.contains(model)) {
                models.add(model);
            }
        }
        providers.replaceModels(id, models, clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    public void fetchModels(Context ctx) {
        String id = ctx.pathParam("id");
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        if ("manual".equals(id)) {
            throw new GateException(GateErrorCode.USAGE, "manual provider has no upstream");
        }
        List<String> models = modelFetcher.fetch(p);
        providers.replaceModels(id, models, clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    /**
     * Probes the upstream model list without persisting it, allowing the UI to present
     * a candidate selection list for user review and cherry-picking.
     */
    public void probeModels(Context ctx) {
        String id = ctx.pathParam("id");
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        if ("manual".equals(id)) {
            throw new GateException(GateErrorCode.USAGE, "manual provider has no upstream");
        }
        List<String> models = modelFetcher.fetch(p);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("models", models);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    /**
     * Stores a plaintext API key from the settings center: encrypted via KMS, persisted as
     * {@code kms:<ciphertext>} in {@code api_key_ref}. The plaintext is never echoed back.
     */
    public void setCredential(Context ctx) {
        String id = ctx.pathParam("id");
        ProviderRepository.ProviderRow existing = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        Map<String, Object> req = Json.parseObject(ctx.body());
        String apiKey = str(req, "api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, "api_key is required");
        }
        if (kms == null) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "KMS service is not wired; cannot store credentials");
        }
        String ciphertext = "kms:" + kms.encrypt(apiKey.trim());
        providers.upsert(new ProviderRepository.ProviderRow(existing.id(), existing.name(),
                existing.baseUrl(), ciphertext, existing.type(), existing.createdAt()), clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    /** Clears the stored credential ({@code api_key_ref = "unconfigured"}). */
    public void clearCredential(Context ctx) {
        String id = ctx.pathParam("id");
        ProviderRepository.ProviderRow existing = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        providers.upsert(new ProviderRepository.ProviderRow(existing.id(), existing.name(),
                existing.baseUrl(), "unconfigured", existing.type(), existing.createdAt()), clock.now());
        ctx.status(HttpStatus.OK);
        ctx.json(renderProviderDetail(id));
    }

    private Map<String, Object> renderProviderDetail(String id) {
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("name", p.name());
        body.put("base_url", p.baseUrl());
        body.put("type", p.type());
        body.put("credential_configured", ApiKeyResolver.isConfigured(p.apiKeyRef()));
        List<String> models = providers.models(id);
        body.put("model_count", models.size());
        body.put("models", models);
        return body;
    }

    private static ProviderRepository.ProviderRow parseProvider(Map<String, Object> req, String id,
                                                                 ProviderRepository.ProviderRow existing) {
        String name = required(req, "name");
        String baseUrl = required(req, "base_url");
        String type = required(req, "type");
        // api_key_ref is NOT client-writable here: credentials flow exclusively through the
        // KMS-encrypting /credential endpoint. New rows start unconfigured; edits preserve the ref.
        String apiKeyRef = existing == null ? "unconfigured" : existing.apiKeyRef();
        Instant createdAt = existing == null ? Instant.now() : existing.createdAt();
        return new ProviderRepository.ProviderRow(id, name, baseUrl, apiKeyRef, type, createdAt);
    }

    private static String required(Map<String, Object> req, String key) {
        String value = str(req, key);
        if (value == null || value.isBlank()) {
            throw new GateException(GateErrorCode.USAGE, key + " is required");
        }
        return value.trim();
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? null : val.toString();
    }
}
