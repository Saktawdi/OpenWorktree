package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.web.service.OpenCodeConfigService;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenCode provider-file controller. Owns /api/opencode/providers/*.
 *
 * <p>CRUD over the {@code provider} node of the local OpenCode CLI config file (the same surface
 * the ai-toolbox project manages) — distinct from {@link ProviderController}, which owns the
 * gate-internal review-engine provider table. Writes mutate only {@code provider}; the rest of the
 * file round-trips untouched, with a {@code .bak} side copy before each save.
 */
public final class OpenCodeProviderController implements WebController {

    private final OpenCodeConfigService configService;

    public OpenCodeProviderController(OpenCodeConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/opencode/config-path", this::configPath);
        app.get("/api/opencode/providers", this::list);
        app.post("/api/opencode/providers/{key}", this::create);
        app.put("/api/opencode/providers/{key}", this::update);
        app.delete("/api/opencode/providers/{key}", this::delete);
    }

    public void configPath(Context ctx) {
        OpenCodeConfigService.ConfigLocation loc = configService.locate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", loc.path().toString());
        body.put("exists", loc.exists());
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void list(Context ctx) {
        Map<String, Object> providers = configService.readProviders();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : providers.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> raw) {
                rows.add(OpenCodeConfigService.renderProvider(e.getKey(), asMap(raw)));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        OpenCodeConfigService.ConfigLocation loc = configService.locate();
        body.put("config_path", loc.path().toString());
        body.put("config_exists", loc.exists());
        body.put("providers", rows);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    public void create(Context ctx) {
        String key = ctx.pathParam("key");
        Map<String, Object> providers = configService.readProviders();
        if (providers.containsKey(key)) {
            throw new GateException(GateErrorCode.USAGE, "opencode provider already exists: " + key);
        }
        providers = configService.upsertProvider(key, Json.parseObject(ctx.body()));
        ctx.status(HttpStatus.CREATED);
        ctx.json(renderOne(key, providers));
    }

    public void update(Context ctx) {
        String key = ctx.pathParam("key");
        Map<String, Object> providers = configService.readProviders();
        if (!providers.containsKey(key)) {
            throw new GateException(GateErrorCode.USAGE, "no such opencode provider: " + key);
        }
        providers = configService.upsertProvider(key, Json.parseObject(ctx.body()));
        ctx.status(HttpStatus.OK);
        ctx.json(renderOne(key, providers));
    }

    public void delete(Context ctx) {
        String key = ctx.pathParam("key");
        configService.deleteProvider(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        ctx.status(HttpStatus.OK);
        ctx.json(body);
    }

    private static Map<String, Object> renderOne(String key, Map<String, Object> providers) {
        Object raw = providers.get(key);
        if (raw instanceof Map<?, ?> m) {
            return OpenCodeConfigService.renderProvider(key, asMap(m));
        }
        throw new GateException(GateErrorCode.GATE_ERROR_IO, "opencode provider vanished after write: " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
