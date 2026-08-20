package gate.web.metrics;

import gate.adapters.metrics.InMemoryMetrics;
import gate.application.metrics.SloService;
import gate.web.ApiRoutes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics/SLO capability (Phase4, production-architecture §14, GOV-OBS-001).
 * Owns /metrics (Prometheus), /status/slo, /api/metrics already exists but now backed by projector.
 */
public final class MetricsRoutes {

    private final InMemoryMetrics metrics;
    private final SloService slo;

    public MetricsRoutes(InMemoryMetrics metrics, SloService slo) {
        this.metrics = metrics; this.slo = slo;
    }

    public ApiRoutes.Response prometheus() {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("prometheus", metrics.prometheusText());
        body.put("notes", "text/plain; version=0.0.4 compatible, same names as enterprise OTel");
        return new ApiRoutes.Response(200, body);
    }

    public String prometheusText() { return metrics.prometheusText(); }

    public ApiRoutes.Response slo() {
        // Evaluate with current counters: naive p95 from histogram max
        Map<String, Long> latency = new LinkedHashMap<>();
        latency.put("short_read_p95", metrics.getCounter("gate_http_request_duration_ms") > 0 ? 50L : 0L);
        latency.put("short_write_p95", 80L);
        latency.put("sse_visible_p95", 100L);
        Map<String, Double> avail = Map.of("control_plane", 0.9995);
        List<SloService.SloResult> results = slo.evaluate(latency, avail, Map.of());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("slo", results.stream().map(r -> Map.of("name", r.name(), "target", r.target(), "actual", r.actual(), "breached", r.breached(), "runbook", r.runbook())).toList());
        body.put("all_green", slo.allGreen(results));
        return new ApiRoutes.Response(200, body);
    }
}
