package gate.web.security;

import gate.domain.error.GateErrorCode;
import gate.ports.store.CredentialRepository;
import gate.web.util.Json;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.HttpResponseException;
import java.util.List;
import java.util.Locale;

/**
 * Per-request authentication + anti-DNS-rebinding gate for Javalin.
 */
public final class AuthFilter {

    private final CredentialRepository credentials;
    private final List<String> allowedHosts;

    public AuthFilter(CredentialRepository credentials, List<String> allowedOrigins) {
        this.credentials = credentials;
        this.allowedHosts = allowedOrigins.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * Authorizes request or halts handling with an unauthorized/forbidden response.
     *
     * @param ctx Javalin Context
     * @param allowQueryToken true for SSE endpoints
     */
    public void authorize(Context ctx, boolean allowQueryToken) {
        if (!hostAllowed(ctx)) {
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "FORBIDDEN",
                    "Host/Origin not in allowed_origins (DNS-rebinding guard, §3.3)", null));
            throw new HttpResponseException(HttpStatus.FORBIDDEN.getCode(), "Forbidden");
        }

        String token = extractToken(ctx, allowQueryToken);
        if (token == null || token.isBlank()) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "UNAUTHORIZED", "missing bearer token", null));
            throw new HttpResponseException(HttpStatus.UNAUTHORIZED.getCode(), "Unauthorized");
        }

        CredentialRepository.Domain domain = credentials.validate(token);
        if (!domain.isValid() || !domain.isHuman()) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(Json.error(GateErrorCode.USAGE.code(), "UNAUTHORIZED",
                    "invalid or non-HUMAN token (Web console requires a HUMAN-domain token, ADR-10)", null));
            throw new HttpResponseException(HttpStatus.UNAUTHORIZED.getCode(), "Unauthorized");
        }
    }

    private boolean hostAllowed(Context ctx) {
        String host = ctx.header("Host");
        if (host != null && !allowedHosts.contains(hostname(host))) {
            return false;
        }
        String origin = ctx.header("Origin");
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

    private static String hostname(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    private static String extractToken(Context ctx, boolean allowQueryToken) {
        String auth = ctx.header("Authorization");
        if (auth != null) {
            String prefix = "Bearer ";
            if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return auth.substring(prefix.length()).trim();
            }
        }
        if (allowQueryToken) {
            return ctx.queryParam("token");
        }
        return null;
    }
}
