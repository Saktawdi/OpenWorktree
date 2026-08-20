package gate.ports.security;

import gate.domain.security.SecurityContext;

/**
 * ThreadLocal holder for SecurityContext, shared between adapters and web.
 * Gate-adapters must not depend on gate-web; this holder lives in ports.
 */
public final class SecurityContextHolder {

    private static final ThreadLocal<SecurityContext> CTX = new ThreadLocal<>();

    private SecurityContextHolder() {}

    public static void set(SecurityContext ctx) { CTX.set(ctx); }
    public static SecurityContext get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
    public static String currentTenant() {
        SecurityContext c = CTX.get();
        return c == null || c.tenantId() == null ? "default" : c.tenantId();
    }
}
