package gate.adapters.security;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.security.SecurityContext;
import gate.domain.security.TenantId;
import gate.ports.security.TenantPort;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tenant isolation enforcer. Validates every DB object belongs to context tenant.
 */
public final class TenantIsolationService implements TenantPort {

    private final JdbcTemplate jdbc;

    public TenantIsolationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public TenantId resolveTenant(SecurityContext ctx) {
        if (ctx == null || ctx.tenantId() == null) return TenantId.DEFAULT;
        return new TenantId(ctx.tenantId());
    }

    @Override
    public void requireTenantAccess(SecurityContext ctx, String resourceTenantId) {
        String ctxTenant = ctx == null ? "default" : ctx.tenantId();
        String resTenant = resourceTenantId == null ? "default" : resourceTenantId;
        if (!ctxTenant.equals(resTenant)) {
            throw new GateException(GateErrorCode.USAGE, "PERMISSION_DENIED: cross-tenant isolation: context=" + ctxTenant + " resource=" + resTenant);
        }
    }

    @Override
    public <T> Optional<T> filterIfTenantMismatch(SecurityContext ctx, String objectTenantId, T object) {
        String ctxTenant = ctx == null ? "default" : ctx.tenantId();
        String objTenant = objectTenantId == null ? "default" : objectTenantId;
        if (!ctxTenant.equals(objTenant)) return Optional.empty();
        return Optional.of(object);
    }

    @Override
    public int backfillDefaultTenant() {
        int a = 0, b = 0;
        try { a = jdbc.update("UPDATE gate_task SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id=''"); } catch (Exception ignored) {}
        try { b = jdbc.update("UPDATE ticket SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id=''"); } catch (Exception ignored) {}
        return a + b;
    }
}
