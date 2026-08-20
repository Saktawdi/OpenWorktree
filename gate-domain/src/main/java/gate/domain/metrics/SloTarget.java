package gate.domain.metrics;

import java.time.Duration;

/**
 * Phase4 SLO targets (production-architecture §14). Single source of truth.
 */
public final class SloTarget {

    private SloTarget() {}

    public static final double CONTROL_PLANE_AVAILABILITY = 0.999; // 99.9%
    public static final Duration SHORT_READ_P95 = Duration.ofMillis(200);
    public static final Duration SHORT_READ_P99 = Duration.ofMillis(500);
    public static final Duration SHORT_WRITE_P95 = Duration.ofMillis(300);
    public static final Duration SHORT_WRITE_P99 = Duration.ofMillis(800);
    public static final Duration TASK_VISIBLE_P99 = Duration.ofSeconds(1);
    public static final Duration WORKER_TAKEOVER_P95 = Duration.ofSeconds(120); // 2 lease cycles * 60s
    public static final Duration SSE_VISIBLE_P95 = Duration.ofSeconds(2);
    public static final Duration RECONCILE_P95 = Duration.ofMinutes(5);
    public static final int SSE_CONNECTIONS_PER_NODE = 1000;
    public static final Duration DB_RPO = Duration.ofMinutes(5);
    public static final Duration CONTROL_PLANE_RTO = Duration.ofMinutes(30);

    /** Alert thresholds (production-architecture §13.4). */
    public static final double STALE_FENCE_SPIKE_PER_MIN = 10;
    public static final double LEASE_EXPIRY_RATE_SPIKE = 0.05;
}
