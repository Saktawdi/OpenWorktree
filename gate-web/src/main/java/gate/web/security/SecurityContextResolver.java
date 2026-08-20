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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SecurityContextResolver(CredentialRepository credentials, RbacPort rbac) {
        this(credentials, rbac, null);
    }

    public SecurityContextResolver(CredentialRepository credentials, RbacPort rbac, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.credentials = credentials; this.rbac = rbac; this.jdbc = jdbc;
    }

    public SecurityContext resolve(String token) {
        if (token == null || token.isBlank()) return SecurityContext.anonymous();
        CredentialRepository.Domain domain = credentials.validate(token);
        if (!domain.isValid()) return SecurityContext.anonymous();
        // Derive userId: for HUMAN token, use hash prefix; for AGENT, ticket binding
        String userId = domain.isHuman() ? "human:" + token.substring(0, Math.min(8, token.length())) : "agent:" + domain.ticketNo();
        // Tenant is resolved from credential.tenant_id (V13) via DB; fallback to default if column missing
        String tenantId = "default";
        if (jdbc != null) {
            try {
                String hash = sha256Hex(token);
                java.util.List<String> rows = jdbc.queryForList("SELECT tenant_id FROM credential WHERE token_hash = ?", String.class, hash);
                if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) tenantId = rows.get(0);
            } catch (Exception ignored) {}
        }
        Set<RbacRole> roles = new HashSet<>();
        try {
            Set<RbacRole> resolved = rbac.resolveRoles(userId, tenantId, null);
            if (resolved != null && !resolved.isEmpty()) roles.addAll(resolved);
            // else remain empty -> fail-closed. No auto-grant for HUMAN.
        } catch (Exception e) {
            // Fail-closed: do not grant Developer on error. Log and keep empty.
            System.err.println("[RBAC] resolveRoles failed for " + userId + ": " + e.getMessage());
        }
        String hash = token.length() > 16 ? token.substring(0,16) : token;
        return new SecurityContext(userId, tenantId, null, Set.copyOf(roles), hash);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
