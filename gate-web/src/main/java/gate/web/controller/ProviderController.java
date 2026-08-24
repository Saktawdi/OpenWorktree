package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.Clock;
import gate.ports.ProviderRepository;
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
 */
public final class ProviderController implements WebController {

    private final ProviderRepository providers;
    private final ProviderModelFetcher modelFetcher;
    private final Clock clock;

    public ProviderController(ProviderRepository providers, ProviderModelFetcher modelFetcher, Clock clock) {
        this.providers = providers;
        this.modelFetcher = modelFetcher;
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
            m.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
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

    private Map<String, Object> renderProviderDetail(String id) {
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() ->
                new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("name", p.name());
        body.put("base_url", p.baseUrl());
        body.put("type", p.type());
        body.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
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
        String apiKeyRef = str(req, "api_key_ref");
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            apiKeyRef = existing == null ? "unconfigured" : existing.apiKeyRef();
        }
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
