package gate.web.security;

import gate.domain.security.SecurityContext;

/**
 * ThreadLocal holder for authenticated SecurityContext (Phase4 ADR-007).
 * Delegates to ports holder so adapters can read tenant without depending on web.
 * @deprecated use gate.ports.security.SecurityContextHolder
 */
@Deprecated
public final class GateSecurityHolder {

    private GateSecurityHolder() {}

    public static void set(SecurityContext ctx) { gate.ports.security.SecurityContextHolder.set(ctx); }
    public static SecurityContext get() { return gate.ports.security.SecurityContextHolder.get(); }
    public static void clear() { gate.ports.security.SecurityContextHolder.clear(); }
}
