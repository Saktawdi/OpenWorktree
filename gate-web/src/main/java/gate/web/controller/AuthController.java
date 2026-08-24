package gate.web.controller;

import gate.domain.error.GateErrorCode;
import gate.ports.CredentialRepository;
import gate.web.util.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication & Token Verification Controller.
 * Owns /api/auth/verify routes.
 */
public final class AuthController implements WebController {

    private final CredentialRepository credentials;

    public AuthController(CredentialRepository credentials) {
        this.credentials = credentials;
    }

    @Override
    public void register(Javalin app) {
        app.post("/api/auth/verify", this::verify);
        app.get("/api/auth/verify", this::methodNotAllowed);
        app.put("/api/auth/verify", this::methodNotAllowed);
        app.delete("/api/auth/verify", this::methodNotAllowed);
    }

    public void verify(Context ctx) {
        String token = extractTokenField(ctx.body());
        CredentialRepository.Domain domain = token == null
                ? CredentialRepository.Domain.invalid()
                : credentials.validate(token);
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
        ctx.json(body);
    }

    private void methodNotAllowed(Context ctx) {
        ctx.status(HttpStatus.METHOD_NOT_ALLOWED);
        ctx.contentType("application/json; charset=utf-8");
        ctx.result(Json.error(GateErrorCode.USAGE.code(), "METHOD_NOT_ALLOWED", "use POST", null));
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
}
