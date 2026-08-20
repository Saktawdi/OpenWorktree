package gate.domain.security;

import java.util.Set;

/**
 * Authenticated principal with tenant isolation (ADR-007 §Tenant/RBAC).
 * Every request, SSE reconnection and worker execution must carry this context.
 * tenantId == "default" for legacy single-tenant data (expand/backfill).
 */
public record SecurityContext(
        String userId,
        String tenantId,
        String projectId,
        Set<RbacRole> roles,
        String tokenHash
) {
    public static SecurityContext anonymous() {
        return new SecurityContext(null, "default", null, Set.of(), null);
    }

    public boolean hasRole(RbacRole role) { return roles != null && roles.contains(role); }

    public boolean hasAny(Set<RbacRole> allowed) {
        if (roles == null) return false;
        for (RbacRole r : allowed) if (roles.contains(r)) return true;
        return false;
    }

    public boolean isTenant(String tenant) {
        if (tenant == null) return true;
        return tenantId != null && tenantId.equals(tenant);
    }
}
