package gate.domain.security;

import java.util.Set;

/**
 * Separation of Duties policy (ADR-007 §Duties separation).
 * High-risk gate policy modification + review + publish must not be performed by same user
 * without explicit exception and dual approval.
 */
public final class SoDPolicy {

    private SoDPolicy() {}

    /**
     * Returns violation message if SoD is breached, null if allowed.
     * Rule: same userId must not hold both REVIEWER and PUBLISHER for same ticket+round
     * unless exceptionApproved == true (dual approval recorded in audit).
     */
    public static String check(String userId, Set<RbacRole> roles, String ticketNo, int round,
                               boolean alreadyReviewed, boolean exceptionApproved) {
        if (userId == null) return null;
        if (roles == null) return null;
        boolean isReviewer = roles.contains(RbacRole.REVIEWER);
        boolean isPublisher = roles.contains(RbacRole.PUBLISHER);
        // If user is both, require exception
        if (isReviewer && isPublisher && alreadyReviewed && !exceptionApproved) {
            return "SoD violation: user " + userId + " cannot publish ticket " + ticketNo
                    + " round " + round + " after reviewing it without dual-approval exception";
        }
        // ProjectAdmin + SystemOperator are allowed to bypass only with audit (still check)
        return null;
    }

    /** Policy version check must be part of PublishAuthorization evidence. */
    public static boolean isPolicyVersionAllowed(String version) {
        return version != null && !version.isBlank();
    }
}
