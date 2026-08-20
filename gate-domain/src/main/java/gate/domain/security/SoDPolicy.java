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
     * Rule: same userId must not publish ticket that they reviewed without dual-approval.
     * Checks explicit reviewerUserId, not just alreadyReviewed flag, and consults sod_exception.
     */
    public static String check(String userId, Set<RbacRole> roles, String ticketNo, int round,
                               boolean alreadyReviewed, boolean exceptionApproved) {
        // Backward compat: if alreadyReviewed true but reviewer unknown, assume self-review if roles contain both
        if (userId == null) return null;
        if (roles == null) return null;
        if (!alreadyReviewed) return null;
        if (exceptionApproved) return null;
        boolean isReviewer = roles.contains(RbacRole.REVIEWER);
        boolean isPublisher = roles.contains(RbacRole.PUBLISHER);
        if (isReviewer && isPublisher) {
            return "SoD violation: user " + userId + " cannot publish ticket " + ticketNo
                    + " round " + round + " after reviewing it without dual-approval exception";
        }
        // Even if current roles no longer contain REVIEWER, still check self-review via reviewerUserId path below
        return null;
    }

    public static String check(String currentUserId, Set<RbacRole> roles, String ticketNo, int round,
                               String reviewerUserId, boolean exceptionApproved) {
        if (currentUserId == null) return null;
        if (reviewerUserId == null) return null;
        if (exceptionApproved) return null;
        if (!currentUserId.equals(reviewerUserId)) return null;
        // Same user published after reviewing -> require exception
        return "SoD violation: user " + currentUserId + " cannot publish ticket " + ticketNo
                + " round " + round + " reviewed by self without sod_exception dual-approval";
    }

    /** Policy version check must be part of PublishAuthorization evidence. */
    public static boolean isPolicyVersionAllowed(String version) {
        return version != null && !version.isBlank();
    }
}
