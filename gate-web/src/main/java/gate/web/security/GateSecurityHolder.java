package gate.web.security;

import gate.domain.security.SecurityContext;

/**
 * ThreadLocal holder for authenticated SecurityContext (Phase4 ADR-007).
 * Set by ApiHandler after AuthFilter, read by ApiRoutes for RBAC/SoD.
 */
public final class GateSecurityHolder {

    private static final ThreadLocal<SecurityContext> CTX = new ThreadLocal<>();

    private GateSecurityHolder() {}

    public static void set(SecurityContext ctx) { CTX.set(ctx); }
    public static SecurityContext get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
}
