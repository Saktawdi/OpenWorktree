package gate.domain.security;

import java.util.Set;

/**
 * Phase4 enterprise RBAC roles (ADR-007, production-architecture §12.1).
 * Minimal set required for SoD and tenancy.
 */
public enum RbacRole {
    DEVELOPER,
    REVIEWER,
    PUBLISHER,
    PROJECT_ADMIN,
    SECURITY_AUDITOR,
    SYSTEM_OPERATOR;

    /** Parse case-insensitive, null-safe. */
    public static RbacRole parse(String raw) {
        if (raw == null) return null;
        try { return valueOf(raw.trim().toUpperCase()); } catch (Exception e) { return null; }
    }

    /** Roles allowed to create tickets / run agents. */
    public static final Set<RbacRole> CAN_DEVELOP = Set.of(DEVELOPER, PROJECT_ADMIN, SYSTEM_OPERATOR);
    /** Roles allowed to review. */
    public static final Set<RbacRole> CAN_REVIEW = Set.of(REVIEWER, PROJECT_ADMIN, SYSTEM_OPERATOR);
    /** Roles allowed to publish. */
    public static final Set<RbacRole> CAN_PUBLISH = Set.of(PUBLISHER, PROJECT_ADMIN, SYSTEM_OPERATOR);
    /** Roles allowed to manage project/members/policy. */
    public static final Set<RbacRole> CAN_ADMIN_PROJECT = Set.of(PROJECT_ADMIN, SYSTEM_OPERATOR);
    /** Read-only audit. */
    public static final Set<RbacRole> CAN_AUDIT = Set.of(SECURITY_AUDITOR, PROJECT_ADMIN, SYSTEM_OPERATOR);
}
