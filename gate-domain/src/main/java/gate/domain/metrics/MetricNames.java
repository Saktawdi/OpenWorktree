package gate.domain.metrics;

/**
 * Canonical metric names (GOV-OBS-001). All dashboards/alerts must use these.
 */
public final class MetricNames {

    private MetricNames() {}

    public static final String HTTP_REQUESTS = "gate_http_requests_total";
    public static final String HTTP_LATENCY = "gate_http_request_duration_ms";
    public static final String TASK_QUEUE_DEPTH = "gate_task_queue_depth";
    public static final String TASK_LEASE_EXPIRY = "gate_task_lease_expiry_total";
    public static final String TASK_STALE_REJECTIONS = "gate_task_stale_rejections_total";
    public static final String TASK_TAKEOVER_LATENCY = "gate_task_takeover_duration_ms";
    public static final String GIT_CAS_CONFLICT = "gate_git_cas_conflict_total";
    public static final String GIT_UNKNOWN_RECONCILE = "gate_git_unknown_reconcile_duration_ms";
    public static final String SSE_CONNECTIONS = "gate_sse_connections";
    public static final String SSE_DROPPED = "gate_sse_dropped_events_total";
    public static final String DB_POOL_ACTIVE = "gate_db_pool_active";
    public static final String AUDIT_CHECKPOINT_FAILURE = "gate_audit_checkpoint_failure_total";
    public static final String BACKUP_FAILURE = "gate_backup_failure_total";
}
