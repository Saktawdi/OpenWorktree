package gate.ports.security;

import gate.domain.security.Permission;
import gate.domain.security.RbacRole;
import gate.domain.security.SecurityContext;
import java.util.Set;

/**
 * RBAC port (ADR-007). Application layer checks permissions; adapter resolves roles from DB.
 */
public interface RbacPort {

    /** Resolve roles for a user in a tenant/project. Returns empty if unknown. */
    Set<RbacRole> resolveRoles(String userId, String tenantId, String projectId);

    /** Check whether context has permission on resource. */
    boolean hasPermission(SecurityContext ctx, Permission permission, String resourceTenant, String resourceProject);

    /** Require permission or throw GateException(403). */
    void require(SecurityContext ctx, Permission permission, String resourceTenant, String resourceProject);

    /** Assign role (admin only). */
    void assignRole(String userId, String tenantId, String projectId, RbacRole role, String grantedBy);

    /** Revoke role. */
    void revokeRole(String userId, String tenantId, String projectId, RbacRole role, String revokedBy);
}
