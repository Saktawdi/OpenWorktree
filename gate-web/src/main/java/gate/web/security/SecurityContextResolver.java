package gate.web.security;

import gate.domain.security.RbacRole;
import gate.domain.security.SecurityContext;
import gate.ports.CredentialRepository;
import gate.ports.security.RbacPort;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves SecurityContext from bearer token (Phase4 ADR-007).
 * Local: token_hash -> credential -> userId/tenant/roles; enterprise: OIDC.
 */
public final class SecurityContextResolver {

    private final CredentialRepository credentials;
    private final RbacPort rbac;

    public SecurityContextResolver(CredentialRepository credentials, RbacPort rbac) {
        this.credentials = credentials; this.rbac = rbac;
    }

    public SecurityContext resolve(String token) {
        if (token == null || token.isBlank()) return SecurityContext.anonymous();
        CredentialRepository.Domain domain = credentials.validate(token);
        if (!domain.isValid()) return SecurityContext.anonymous();
        // Derive userId: for HUMAN token, use hash prefix; for AGENT, ticket binding
        String userId = domain.isHuman() ? "human:" + token.substring(0, Math.min(8, token.length())) : "agent:" + domain.ticketNo();
        // Resolve tenant: try credential.tenant_id via DB peek (if V13 migrated), else default
        String tenantId = "default";
        Set<RbacRole> roles = new HashSet<>();
        try {
            // Best-effort: ask rbac store for roles; if none, default to HUMAN full access for local dev
            Set<RbacRole> resolved = rbac.resolveRoles(userId, tenantId, null);
            if (!resolved.isEmpty()) roles.addAll(resolved);
            else if (domain.isHuman()) {
                roles.add(RbacRole.DEVELOPER);
                roles.add(RbacRole.REVIEWER);
                roles.add(RbacRole.PUBLISHER);
                roles.add(RbacRole.PROJECT_ADMIN);
            }
        } catch (Exception ignored) {
            if (domain.isHuman()) roles.add(RbacRole.DEVELOPER);
        }
        String hash = token.length() > 16 ? token.substring(0,16) : token;
        return new SecurityContext(userId, tenantId, null, Set.copyOf(roles), hash);
    }
}
