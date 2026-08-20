package gate.web.provider;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.ProviderRepository;
import gate.web.ApiRoutes;
import gate.ports.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider capability extraction (Phase4 GOV-CPLX-001).
 * Extracted from ApiRoutes to reduce facade lines.
 */
public final class ProviderRoutes {

    private final ProviderRepository providers;
    private final Clock clock;

    public ProviderRoutes(ProviderRepository providers, Clock clock) {
        this.providers = providers; this.clock = clock;
    }

    public ApiRoutes.Response list() {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (ProviderRepository.ProviderRow p : providers.findAll()) {
            if (p.id().equals("cli-default")) continue;
            List<String> models = providers.models(p.id());
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", p.id()); m.put("name", p.name()); m.put("base_url", p.baseUrl());
            m.put("type", p.type()); m.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
            m.put("model_count", models.size()); m.put("models", models);
            rows.add(m);
        }
        Map<String,Object> body = new LinkedHashMap<>(); body.put("providers", rows);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response create(String requestBody) {
        Map<String,Object> req = parseObject(requestBody);
        String id = required(req, "id");
        if (providers.find(id).isPresent()) throw new GateException(GateErrorCode.USAGE, "provider already exists: " + id);
        ProviderRepository.ProviderRow row = parseProvider(req, id, null);
        providers.upsert(row, clock.now());
        return detail(id);
    }

    public ApiRoutes.Response update(String id, String requestBody) {
        ProviderRepository.ProviderRow existing = providers.find(id).orElseThrow(() -> new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        ProviderRepository.ProviderRow row = parseProvider(parseObject(requestBody), id, existing);
        providers.upsert(row, clock.now());
        return detail(id);
    }

    public ApiRoutes.Response delete(String id) {
        if (providers.find(id).isEmpty()) throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        if ("manual".equals(id) || "cli-default".equals(id)) throw new GateException(GateErrorCode.USAGE, "manual provider cannot be deleted");
        providers.delete(id);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("ok", true);
        return new ApiRoutes.Response(200, body);
    }

    public ApiRoutes.Response modelsUpdate(String id, String requestBody) {
        if (providers.find(id).isEmpty()) throw new GateException(GateErrorCode.USAGE, "no such provider: " + id);
        Map<String,Object> req = parseObject(requestBody);
        Object rawModels = req.get("models");
        if (!(rawModels instanceof List<?> list)) throw new GateException(GateErrorCode.USAGE, "models must be an array");
        List<String> models = new ArrayList<>();
        for (Object v : list) { String model = v==null?"":v.toString().trim(); if (!model.isBlank() && !models.contains(model)) models.add(model); }
        providers.replaceModels(id, models, clock.now());
        return detail(id);
    }

    public ApiRoutes.Response detail(String id) {
        ProviderRepository.ProviderRow p = providers.find(id).orElseThrow(() -> new GateException(GateErrorCode.USAGE, "no such provider: " + id));
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("id", p.id()); body.put("name", p.name()); body.put("base_url", p.baseUrl()); body.put("type", p.type());
        body.put("credential_configured", p.apiKeyRef() != null && !p.apiKeyRef().isBlank());
        List<String> models = providers.models(id); body.put("model_count", models.size()); body.put("models", models);
        return new ApiRoutes.Response(200, body);
    }

    private static ProviderRepository.ProviderRow parseProvider(Map<String,Object> req, String id, ProviderRepository.ProviderRow existing) {
        String name = required(req, "name"); String baseUrl = required(req, "base_url"); String type = required(req, "type");
        String apiKeyRef = str(req, "api_key_ref");
        if (apiKeyRef == null || apiKeyRef.isBlank()) apiKeyRef = existing == null ? "unconfigured" : existing.apiKeyRef();
        Instant createdAt = existing == null ? Instant.now() : existing.createdAt();
        return new ProviderRepository.ProviderRow(id, name, baseUrl, apiKeyRef, type, createdAt);
    }
    private static String required(Map<String,Object> req, String key) {
        String v = str(req, key); if (v==null || v.isBlank()) throw new GateException(GateErrorCode.USAGE, key+" is required"); return v.trim();
    }
    private static String str(Map<String,Object> m, String k) { Object v=m.get(k); return v==null?null:v.toString(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> parseObject(String body) {
        if (body==null||body.isBlank()) return Map.of();
        try { Object parsed = gate.application.MiniJson.parse(body.trim()); if (parsed instanceof Map<?,?> mm) return (Map<String,Object>) mm; } catch (Exception e) { throw new GateException(GateErrorCode.USAGE, "malformed JSON body"); }
        throw new GateException(GateErrorCode.USAGE, "request body must be a JSON object");
    }
}
