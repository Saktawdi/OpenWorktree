package gate.domain.security;

/**
 * Fine-grained permissions mapped from RbacRole. One permission = one use-case guard.
 * Keeps HTTP/CLI drivers thin: they call RbacService.require(Permission).
 */
public enum Permission {
    TICKET_CREATE,
    TICKET_VIEW,
    TICKET_EDIT,
    PRESUBMIT_CREATE,
    REVIEW_RUN,
    REVIEW_VIEW,
    PUBLISH_RUN,
    RECONCILE_RUN,
    PROJECT_CREATE,
    PROJECT_VIEW,
    PROJECT_DELETE,
    SESSION_CREATE,
    SESSION_VIEW,
    SESSION_ABORT,
    PROVIDER_MANAGE,
    METRICS_VIEW,
    STATUS_VIEW,
    AUDIT_VIEW,
    CONFIG_VIEW,
    TENANT_ADMIN
}
