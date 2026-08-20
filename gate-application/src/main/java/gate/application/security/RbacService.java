package gate.application.security;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.security.Permission;
import gate.domain.security.RbacRole;
import gate.domain.security.SecurityContext;
import gate.ports.security.RbacPort;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Application RBAC enforcer (ADR-007). No DB access directly, delegates to RbacPort.
 * L3 implementation with L4-approved permission map.
 */
public final class RbacService {

    private static final Map<Permission, Set<RbacRole>> PERMISSION_MAP = new EnumMap<>(Permission.class);

    static {
        PERMISSION_MAP.put(Permission.TICKET_CREATE, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.TICKET_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.REVIEWER, RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SECURITY_AUDITOR, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.TICKET_EDIT, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN));
        PERMISSION_MAP.put(Permission.PRESUBMIT_CREATE, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.REVIEW_RUN, Set.of(RbacRole.REVIEWER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.REVIEW_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.REVIEWER, RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SECURITY_AUDITOR, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.PUBLISH_RUN, Set.of(RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.RECONCILE_RUN, Set.of(RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.PROJECT_CREATE, Set.of(RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.PROJECT_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.REVIEWER, RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SECURITY_AUDITOR, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.PROJECT_DELETE, Set.of(RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.SESSION_CREATE, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.SESSION_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.REVIEWER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.SESSION_ABORT, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.PROVIDER_MANAGE, Set.of(RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.METRICS_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.PROJECT_ADMIN, RbacRole.SECURITY_AUDITOR, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.STATUS_VIEW, Set.of(RbacRole.DEVELOPER, RbacRole.REVIEWER, RbacRole.PUBLISHER, RbacRole.PROJECT_ADMIN, RbacRole.SECURITY_AUDITOR, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.AUDIT_VIEW, Set.of(RbacRole.SECURITY_AUDITOR, RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.CONFIG_VIEW, Set.of(RbacRole.PROJECT_ADMIN, RbacRole.SYSTEM_OPERATOR));
        PERMISSION_MAP.put(Permission.TENANT_ADMIN, Set.of(RbacRole.SYSTEM_OPERATOR));
    }

    private final RbacPort rbacPort;

    public RbacService(RbacPort rbacPort) { this.rbacPort = rbacPort; }

    public void require(SecurityContext ctx, Permission permission, String resourceTenant, String resourceProject) {
        if (ctx == null) throw new GateException(GateErrorCode.USAGE, "missing security context");
        // Tenant isolation first (ADR-007: every check is tenant-scoped)
        if (resourceTenant != null && !resourceTenant.equals(ctx.tenantId())) {
            throw new GateException(GateErrorCode.USAGE, "PERMISSION_DENIED: cross-tenant access denied: " + ctx.tenantId() + " -> " + resourceTenant);
        }
        Set<RbacRole> allowed = PERMISSION_MAP.get(permission);
        if (allowed == null) throw new GateException(GateErrorCode.INTERNAL, "unknown permission: " + permission);
        if (ctx.roles() != null && ctx.roles().stream().anyMatch(allowed::contains)) return;
        // Also ask port for expanded resolution (DB-backed)
        if (rbacPort != null && rbacPort.hasPermission(ctx, permission, resourceTenant, resourceProject)) return;
        throw new GateException(GateErrorCode.USAGE, "PERMISSION_DENIED: requires " + permission + " roles " + allowed + " but have " + ctx.roles());
    }

    public static Set<RbacRole> allowedRoles(Permission p) { return PERMISSION_MAP.getOrDefault(p, Set.of()); }
}
