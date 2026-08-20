package gate.ports.security;

import gate.domain.security.SecurityContext;
import gate.domain.security.TenantId;
import java.util.Optional;

/**
 * Tenant isolation port (ADR-007).
 * Every DB read/write must be tenant-scoped.
 */
public interface TenantPort {

    /** Resolve tenant from token/context. Returns DEFAULT if not present (legacy). */
    TenantId resolveTenant(SecurityContext ctx);

    /** Enforce tenant match or throw 403. */
    void requireTenantAccess(SecurityContext ctx, String resourceTenantId);

    /** Validate that object belongs to tenant; returns empty if cross-tenant. */
    <T> Optional<T> filterIfTenantMismatch(SecurityContext ctx, String objectTenantId, T object);

    /** Expand/backfill helper: assign default tenant to orphan rows. */
    int backfillDefaultTenant();
}
