package gate.adapters.security;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.security.Permission;
import gate.domain.security.RbacRole;
import gate.domain.security.SecurityContext;
import gate.ports.security.RbacPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB-backed RBAC (Phase4). Table gate_user_role(tenant_id, project_id, user_id, role, granted_by, granted_at, revoked_at).
 * Enterprise: separate admin DB / IdP; local: SQLite store.
 */
public final class JdbcRbacStore implements RbacPort {

    private final JdbcTemplate jdbc;

    public JdbcRbacStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Set<RbacRole> resolveRoles(String userId, String tenantId, String projectId) {
        if (userId == null) return Set.of();
        String tid = tenantId == null ? "default" : tenantId;
        List<String> rows = jdbc.queryForList(
                "SELECT role FROM gate_user_role WHERE user_id=? AND tenant_id=? AND (project_id IS NULL OR project_id=?) AND revoked_at IS NULL",
                String.class, userId, tid, projectId);
        Set<RbacRole> out = new HashSet<>();
        for (String r : rows) {
            RbacRole role = RbacRole.parse(r);
            if (role != null) out.add(role);
        }
        return Set.copyOf(out);
    }

    @Override
    public boolean hasPermission(SecurityContext ctx, Permission permission, String resourceTenant, String resourceProject) {
        if (ctx == null) return false;
        Set<RbacRole> allowed = gate.application.security.RbacService.allowedRoles(permission);
        Set<RbacRole> userRoles = ctx.roles();
        // If context already has roles, use them; otherwise resolve
        if (userRoles == null || userRoles.isEmpty()) {
            userRoles = resolveRoles(ctx.userId(), ctx.tenantId(), resourceProject);
        }
        for (RbacRole r : userRoles) if (allowed.contains(r)) return true;
        return false;
    }

    @Override
    public void require(SecurityContext ctx, Permission permission, String resourceTenant, String resourceProject) {
        if (!hasPermission(ctx, permission, resourceTenant, resourceProject)) {
            throw new GateException(GateErrorCode.USAGE, "PERMISSION_DENIED: " + permission + " for user " + ctx.userId() + " roles " + ctx.roles());
        }
    }

    @Override
    public void assignRole(String userId, String tenantId, String projectId, RbacRole role, String grantedBy) {
        String tid = tenantId == null ? "default" : tenantId;
        jdbc.update("INSERT OR IGNORE INTO gate_user_role(user_id, tenant_id, project_id, role, granted_by, granted_at) VALUES (?,?,?,?,?,datetime('now'))",
                userId, tid, projectId, role.name(), grantedBy);
        // If was revoked, re-activate
        jdbc.update("UPDATE gate_user_role SET revoked_at=NULL, granted_by=?, granted_at=datetime('now') WHERE user_id=? AND tenant_id=? AND ((project_id IS NULL AND ? IS NULL) OR project_id=?) AND role=?",
                grantedBy, userId, tid, projectId, projectId, role.name());
    }

    @Override
    public void revokeRole(String userId, String tenantId, String projectId, RbacRole role, String revokedBy) {
        String tid = tenantId == null ? "default" : tenantId;
        jdbc.update("UPDATE gate_user_role SET revoked_at=datetime('now') WHERE user_id=? AND tenant_id=? AND ((project_id IS NULL AND ? IS NULL) OR project_id=?) AND role=? AND revoked_at IS NULL",
                userId, tid, projectId, projectId, role.name());
    }
}
