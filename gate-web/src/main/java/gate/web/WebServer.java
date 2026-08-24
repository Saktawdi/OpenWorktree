package gate.web;

import gate.domain.config.GateConfig;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.CredentialRepository;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Javalin-based WebServer implementation for gate-web.
 */
final class WebServer implements AutoCloseable {

    private static final String ROOT = "/static";
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".json", "application/json; charset=utf-8",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon",
            ".png", "image/png",
            ".woff2", "font/woff2");

    private final Javalin app;
    private final WebComponents components;
    private final int port;

    WebServer(WebComponents components) {
        this.components = components;
        GateConfig config = components.config();
        GateConfig.WebConfig web = config.web();
        AuthFilter authFilter = new AuthFilter(components.credentials(), web.allowedOrigins());

        this.app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(Json.mapper()));
            cfg.showJavalinBanner = false;
        });

        // Global access log & token masking
        app.after(ctx -> {
            System.err.println("gate-web: " + Http.accessLine(ctx, ctx.status().getCode()));
        });

        // Global Exception Handling -> standard JSON error envelope
        app.exception(GateException.class, (e, ctx) -> {
            int code = gate.web.HttpStatus.forGateError(e.code());
            ctx.status(code);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(e.code().code(), e.code().name(), e.getMessage(), null));
        });

        app.exception(Exception.class, (e, ctx) -> {
            ctx.status(500);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.INTERNAL.code(), "INTERNAL", "internal error", null));
        });

        // Whitelist endpoints (No auth required)
        app.get("/api/health", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("service", "gate-web");
            ctx.status(HttpStatus.OK);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.write(body));
        });

        app.post("/api/auth/verify", ctx -> {
            String token = extractTokenField(ctx.body());
            CredentialRepository.Domain domain = token == null
                    ? CredentialRepository.Domain.invalid()
                    : components.credentials().validate(token);
            if (!domain.isValid() || !domain.isHuman()) {
                ctx.status(HttpStatus.UNAUTHORIZED);
                ctx.contentType("application/json; charset=utf-8");
                ctx.result(Json.error(GateErrorCode.USAGE.code(), "UNAUTHORIZED", "invalid or non-HUMAN token", null));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("domain", "HUMAN");
            ctx.status(HttpStatus.OK);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.write(body));
        });

        // Wrong method on /api/auth/verify -> 405
        app.get("/api/auth/verify", ctx -> {
            ctx.status(HttpStatus.METHOD_NOT_ALLOWED);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "METHOD_NOT_ALLOWED", "use POST", null));
        });
        app.put("/api/auth/verify", ctx -> {
            ctx.status(HttpStatus.METHOD_NOT_ALLOWED);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "METHOD_NOT_ALLOWED", "use POST", null));
        });
        app.delete("/api/auth/verify", ctx -> {
            ctx.status(HttpStatus.METHOD_NOT_ALLOWED);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "METHOD_NOT_ALLOWED", "use POST", null));
        });

        // AuthFilter for all other /api/* endpoints
        app.before("/api/*", ctx -> {
            String path = ctx.path();
            if (path.equals("/api/health") || path.equals("/api/auth/verify")) {
                return;
            }
            boolean sse = path.endsWith("/events");
            authFilter.authorize(ctx, sse);
        });

        // Register main API routes
        ApiRoutes apiRoutes = new ApiRoutes(components);
        apiRoutes.register(app);

        // Fallback for API 404
        app.after("/api/*", ctx -> {
            if (ctx.status() == HttpStatus.NOT_FOUND && (ctx.result() == null || ctx.result().isBlank())) {
                ctx.contentType("application/json; charset=utf-8");
                ctx.result(Json.error(GateErrorCode.USAGE.code(), "NOT_FOUND",
                        "no such endpoint: " + ctx.method() + " " + ctx.path(), null));
            }
        });

        // SPA Static File Serving with History Fallback
        app.get("/*", ctx -> {
            String path = ctx.path();
            if (path.startsWith("/api/")) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            if (path.equals("/") || path.isBlank()) {
                path = "/index.html";
            }
            byte[] body = readResource(path);
            String contentType;
            if (body == null) {
                body = readResource("/index.html");
                contentType = "text/html; charset=utf-8";
                if (body == null) {
                    ctx.status(HttpStatus.NOT_FOUND);
                    return;
                }
            } else {
                contentType = contentType(path);
            }
            ctx.status(HttpStatus.OK);
            ctx.contentType(contentType);
            ctx.result(body);
        });

        app.start(web.bind(), web.port());
        this.port = app.port();
    }

    private static String extractTokenField(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(body.trim());
            Object token = m.get("token");
            return token == null ? null : token.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    void start() {
    }

    int port() {
        return port;
    }

    private static byte[] readResource(String path) {
        String normalized = normalize(path);
        if (normalized == null) {
            return null;
        }
        String resource = ROOT + normalized;
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.contains("..") || p.contains("\\")) {
            return null;
        }
        return p;
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot).toLowerCase(java.util.Locale.ROOT);
            String type = CONTENT_TYPES.get(ext);
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public void close() {
        try {
            app.stop();
        } finally {
            components.close();
        }
    }
}
