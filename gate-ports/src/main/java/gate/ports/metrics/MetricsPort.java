package gate.ports.metrics;

import java.time.Duration;
import java.util.Map;

/**
 * Telemetry port (production-architecture §13). Local: in-memory; enterprise: OTel/Prometheus.
 * GOV-OBS-001 requires every task/extern effect to have metrics/trace/errorCode.
 */
public interface MetricsPort {

    void counter(String name, long delta, Map<String, String> labels);
    void histogram(String name, long valueMs, Map<String, String> labels);
    void gauge(String name, double value, Map<String, String> labels);

    /** Record HTTP request latency for SLO. */
    default void recordHttp(String method, String route, int status, Duration latency) {
        counter("gate_http_requests_total", 1, Map.of("method", method, "route", route, "status", String.valueOf(status)));
        histogram("gate_http_request_duration_ms", latency.toMillis(), Map.of("route", route));
    }

    Map<String, Double> snapshotGauges();
    Map<String, Long> snapshotCounters();
}
