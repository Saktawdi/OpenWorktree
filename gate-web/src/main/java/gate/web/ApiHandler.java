package gate.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.CredentialRepository;
import java.io.IOException;
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

    ApiHandler(WebComponents components, AuthFilter authFilter) {
        this.credentials = components.credentials();
        this.authFilter = authFilter;
        this.routes = new ApiRoutes(components);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        int status = 200;
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // §3.4 whitelist: no token required.
            if (path.equals("/api/health")) {
                status = health(exchange);
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
            System.err.println("gate-web: " + Http.accessLine(exchange, status));
            exchange.close();
        }
    }

    private int health(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "gate-web");
        Http.json(exchange, 200, Json.write(body));
        return 200;
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
