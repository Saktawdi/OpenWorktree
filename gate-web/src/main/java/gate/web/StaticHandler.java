package gate.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serves the SPA static bundle from the classpath ({@code /static/**}) with history-mode fallback
 * (执行文档-后端-web §2.5).
 *
 * <p>Anything not under {@code /api/*} lands here. A request for an existing file is served with a
 * best-effort content type; a request that does not resolve to a file returns {@code index.html} so
 * the Vue router can handle the path client-side. Static resources are NOT token-checked (§3.4).
 *
 * <p>Path traversal is refused: any resolved resource path must stay under {@code /static}.
 */
final class StaticHandler implements HttpHandler {

    // The Vite build writes the production bundle to gate-web/src/main/resources/static.
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

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isBlank()) {
                path = "/index.html";
            }
            byte[] body = readResource(path);
            String contentType;
            if (body == null) {
                // SPA history fallback.
                body = readResource("/index.html");
                contentType = "text/html; charset=utf-8";
                if (body == null) {
                    notFound(exchange);
                    return;
                }
            } else {
                contentType = contentType(path);
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static byte[] readResource(String path) throws IOException {
        String normalized = normalize(path);
        if (normalized == null) {
            return null;
        }
        String resource = ROOT + normalized;
        try (InputStream in = StaticHandler.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }

    /** Normalises a request path, refusing traversal outside the bundle. Returns null if unsafe. */
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

    private static void notFound(HttpExchange exchange) throws IOException {
        byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
