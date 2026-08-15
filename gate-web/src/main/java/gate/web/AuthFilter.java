package gate.web;

import com.sun.net.httpserver.HttpExchange;
import gate.domain.error.GateErrorCode;
import gate.ports.CredentialRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-request authentication + anti-DNS-rebinding gate (执行文档-后端-web §3.3, §3.4, §3.4.1).
 *
 * <p>This runs on every {@code /api/*} request except the §3.4 whitelist ({@code /api/auth/verify},
 * {@code /api/health}). Static resources are served outside the {@code /api} tree and never pass
 * through here.
 *
 * <p>Three checks, all fail-closed:
 * <ol>
 *   <li><b>Host/Origin whitelist</b> — the {@code Host} header's hostname must be in
 *       {@code allowed_origins}; if an {@code Origin} header is present its hostname must match too.
 *       This blocks DNS-rebinding even if the bearer token leaks.</li>
 *   <li><b>Bearer token</b> — {@code Authorization: Bearer <token>} is validated against the
 *       credential store; only a HUMAN-domain token is accepted (ADR-10). SSE endpoints additionally
 *       accept {@code ?token=} (browsers' EventSource cannot set headers, §3.4.1).</li>
 * </ol>
 *
 * <p>A rejected request gets a structured JSON error and the exchange is closed; the wrapped handler
 * never runs.
 */
final class AuthFilter {

    private final CredentialRepository credentials;
    private final List<String> allowedHosts;

    AuthFilter(CredentialRepository credentials, List<String> allowedOrigins) {
        this.credentials = credentials;
        this.allowedHosts = allowedOrigins.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * @param allowQueryToken true for SSE endpoints that may carry {@code ?token=} (§3.4.1)
     * @return true if the request is authorised; false if a rejection response was already written
     */
    boolean authorize(HttpExchange exchange, boolean allowQueryToken) throws IOException {
        if (!hostAllowed(exchange)) {
            reject(exchange, 403, GateErrorCode.USAGE.code(),
                    "Host/Origin not in allowed_origins (DNS-rebinding guard, §3.3)");
            return false;
        }
        String token = extractToken(exchange, allowQueryToken);
        if (token == null || token.isBlank()) {
            reject(exchange, 401, GateErrorCode.USAGE.code(), "missing bearer token");
            return false;
        }
        CredentialRepository.Domain domain = credentials.validate(token);
        if (!domain.isValid() || !domain.isHuman()) {
            reject(exchange, 401, GateErrorCode.USAGE.code(),
                    "invalid or non-HUMAN token (Web console requires a HUMAN-domain token, ADR-10)");
            return false;
        }
        return true;
    }

    private boolean hostAllowed(HttpExchange exchange) {
        String host = firstHeader(exchange, "Host");
        if (host != null && !allowedHosts.contains(hostname(host))) {
            return false;
        }
        String origin = firstHeader(exchange, "Origin");
        if (origin != null && !origin.isBlank()) {
            String originHost = hostname(stripScheme(origin));
            return allowedHosts.contains(originHost);
        }
        return true;
    }

    private static String stripScheme(String origin) {
        int idx = origin.indexOf("://");
        return idx < 0 ? origin : origin.substring(idx + 3);
    }

    /** Extracts the hostname from a {@code host[:port]} value, lowercased. */
    private static String hostname(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        // IPv6 literal [::1]:port
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    private static String extractToken(HttpExchange exchange, boolean allowQueryToken) {
        String auth = firstHeader(exchange, "Authorization");
        if (auth != null) {
            String prefix = "Bearer ";
            if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return auth.substring(prefix.length()).trim();
            }
        }
        if (allowQueryToken) {
            return queryParam(exchange, "token").orElse(null);
        }
        return null;
    }

    static Optional<String> queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return Optional.of(java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static void reject(HttpExchange exchange, int status, int errorCode, String message)
            throws IOException {
        byte[] body = Json.error(errorCode, "UNAUTHORIZED", message, null)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
