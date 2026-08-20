package gate.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.CredentialRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code /api/*} router (执行文档-后端-web §4).
 *
 * <p>Two paths are on the §3.4 token whitelist and handled inline: {@code /api/health} and
 * {@code /api/auth/verify}. Everything else first passes {@link AuthFilter} (Host/Origin whitelist +
 * HUMAN bearer token; SSE endpoints also accept {@code ?token=}), then delegates to
 * {@link ApiRoutes}. A {@link GateException} maps to its HTTP status (§4.4); any other exception is a
 * fail-closed 500 with no stack trace in the body.
 */
final class ApiHandler implements HttpHandler {

    private final CredentialRepository credentials;
    private final AuthFilter authFilter;
    private final ApiRoutes routes;
    private final SseHandler sseHandler;
    private final SessionSseHandler sessionSseHandler;
    private final WebComponents components;

    ApiHandler(WebComponents components, AuthFilter authFilter) {
        this.credentials = components.credentials();
        this.authFilter = authFilter;
        this.components = components;
        this.routes = new ApiRoutes(components);
        this.sseHandler = new SseHandler(components.taskRegistry());
        this.sessionSseHandler = new SessionSseHandler(components.agentSessionPort(),
                components.sessionRepository());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startNanos = System.nanoTime();
        int status = 200;
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // §3.4 whitelist + Phase4 health: no token required for liveness/readyz (§13.3)
            if (path.equals("/api/health") || path.equals("/livez")) {
                status = health(exchange);
                return;
            }
            if (path.equals("/readyz")) {
                status = readyz(exchange);
                return;
            }
            if (path.equals("/metrics") || path.equals("/api/metrics/prometheus")) {
                status = metrics(exchange);
                return;
            }
            if (path.equals("/status/dependencies") || path.equals("/api/status/dependencies")) {
                status = dependencies(exchange);
                return;
            }
            if (path.equals("/status/slo") || path.equals("/api/status/slo")) {
                status = slo(exchange);
                return;
            }
            if (path.equals("/api/auth/verify")) {
                status = authVerify(exchange, method);
                return;
            }

            // Everything else requires a HUMAN token + Host/Origin whitelist.
            boolean sse = path.endsWith("/events");
            if (!authFilter.authorize(exchange, sse)) {
                status = 401; // AuthFilter already wrote the body.
                return;
            }
            // Phase4: resolve SecurityContext for RBAC/tenant/SoD (§12.1, ADR-007)
            try {
                String token = extractBearer(exchange, sse);
                var ctx = components.securityResolver().resolve(token);
                gate.web.security.GateSecurityHolder.set(ctx);
            } catch (Exception ignored) { gate.web.security.GateSecurityHolder.set(gate.domain.security.SecurityContext.anonymous()); }
            // Phase4: record metrics + tracing per request (§13.1)
            try { components.metricsPort().counter("gate_http_requests_total",1, Map.of("route", path)); } catch (Exception ignored) {}

            // SSE endpoints do not read a request body and do not use the normal JSON envelope.
            if (sse) {
                String taskId = sseTaskId(path);
                if (taskId != null) {
                    status = sseHandler.handle(exchange, taskId);
                    return;
                }
                String sessionId = sseSessionId(path);
                if (sessionId != null) {
                    status = sessionSseHandler.handle(exchange, sessionId);
                    return;
                }
            }

            String requestBody = Http.readBodyString(exchange);
            ApiRoutes.Response res = routes.route(method, path, requestBody);
            if (res.body() == null) {
                status = res.status() == 200 ? 404 : res.status();
                Http.json(exchange, status, Json.error(GateErrorCode.USAGE.code(),
                        "NOT_FOUND", "no such endpoint: " + method + " " + path, null));
            } else {
                status = res.status();
                Http.json(exchange, status, Json.write(res.body()));
            }
        } catch (GateException e) {
            status = HttpStatus.forGateError(e.code());
            Http.json(exchange, status, Json.error(e.code().code(), e.code().name(), e.getMessage(), null));
        } catch (Exception e) {
            // fail-closed: never leak a stack trace to the response body (§4.4).
            status = 500;
            Http.json(exchange, 500, Json.error(GateErrorCode.INTERNAL.code(),
                    "INTERNAL", "internal error", null));
        } finally {
            try { long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000; components.metricsPort().histogram("gate_http_request_duration_ms", elapsedMs, Map.of("route", exchange != null ? exchange.getRequestURI().getPath() : "unknown")); } catch (Exception ignored) {}
            gate.web.security.GateSecurityHolder.clear();
            System.err.println("gate-web: " + Http.accessLine(exchange, status));
            exchange.close();
        }
    }

    /** Extracts a task id from {@code /api/tasks/{id}/events}, or null if the path is not task SSE. */
    private static String sseTaskId(String path) {
        String prefix = "/api/tasks/";
        String suffix = "/events";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String id = path.substring(prefix.length(), path.length() - suffix.length());
        return id.isEmpty() ? null : id;
    }

    /** Extracts a session id from {@code /api/sessions/{sid}/events}, or null if not a session SSE. */
    private static String sseSessionId(String path) {
        String prefix = "/api/sessions/";
        String suffix = "/events";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String id = path.substring(prefix.length(), path.length() - suffix.length());
        return id.isEmpty() ? null : id;
    }

    private int health(HttpExchange exchange) throws IOException {
        Map<String, Object> body = components.healthRoutes().livez().body() instanceof Map ? (Map<String,Object>) components.healthRoutes().livez().body() : new LinkedHashMap<>(Map.of("status","ok","service","gate-web"));
        Http.json(exchange, 200, Json.write(body));
        return 200;
    }

    private int readyz(HttpExchange exchange) throws IOException {
        var res = components.healthRoutes().readyz();
        Http.json(exchange, res.status(), Json.write(res.body()));
        return res.status();
    }

    private int dependencies(HttpExchange exchange) throws IOException {
        var res = components.healthRoutes().dependencies();
        Http.json(exchange, res.status(), Json.write(res.body()));
        return res.status();
    }

    private int metrics(HttpExchange exchange) throws IOException {
        String text = components.metricsRoutes().prometheusText();
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) { os.write(body); }
        return 200;
    }

    private int slo(HttpExchange exchange) throws IOException {
        var res = components.metricsRoutes().slo();
        Http.json(exchange, res.status(), Json.write(res.body()));
        return res.status();
    }

    private int authVerify(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equals(method)) {
            Http.json(exchange, 405, Json.error(GateErrorCode.USAGE.code(),
                    "METHOD_NOT_ALLOWED", "use POST", null));
            return 405;
        }
        String requestBody = Http.readBodyString(exchange);
        String token = extractTokenField(requestBody);
        CredentialRepository.Domain domain = token == null
                ? CredentialRepository.Domain.invalid()
                : credentials.validate(token);
        if (!domain.isValid() || !domain.isHuman()) {
            Http.json(exchange, 401, Json.error(GateErrorCode.USAGE.code(),
                    "UNAUTHORIZED", "invalid or non-HUMAN token", null));
            return 401;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("domain", "HUMAN");
        Http.json(exchange, 200, Json.write(body));
        return 200;
    }

    private static String extractBearer(HttpExchange exchange, boolean allowQueryToken) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            String prefix = "Bearer ";
            if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) return auth.substring(prefix.length()).trim();
        }
        if (allowQueryToken) {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) for (String pair : query.split("&")) {
                int eq = pair.indexOf('='); String name = eq < 0 ? pair : pair.substring(0, eq);
                if (name.equals("token")) return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq+1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Extracts the {@code token} field from a small JSON body {@code {"token":"..."}}. */
    private static String extractTokenField(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = gate.application.MiniJson.parse(body.trim());
            if (parsed instanceof Map<?, ?> m) {
                Object token = m.get("token");
                return token == null ? null : token.toString();
            }
        } catch (Exception ignored) {
            // malformed body → treated as no token.
        }
        return null;
    }
}
