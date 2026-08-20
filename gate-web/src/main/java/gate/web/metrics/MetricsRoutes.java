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
        long httpP95 = metrics.getP95("gate_http_request_duration_ms");
        long httpP99 = metrics.getP99("gate_http_request_duration_ms");
        Map<String, Long> latency = new LinkedHashMap<>();
        // No clamping: use real p95/p99; short_write should be p95, not p99 (correct naming)
        latency.put("short_read_p95", httpP95);
        latency.put("short_write_p95", httpP95);
        latency.put("short_write_p99", httpP99);
        long sseP95 = metrics.getP95("gate_sse_visible_duration_ms");
        if (sseP95 == 0) sseP95 = httpP95;
        latency.put("sse_visible_p95", sseP95);
        long total = metrics.getCounter("gate_http_requests_total");
        long errors = metrics.getCounter("gate_http_requests_total{status=500}") + metrics.getCounter("gate_http_requests_total{status=503}");
        double avail;
        String availSource;
        if (total == 0) { avail = 1.0; availSource = "no_traffic_no_eval"; }
        else { avail = 1.0 - ((double) errors / Math.max(1, total)); availSource = "real_metrics"; }
        Map<String, Double> availMap = Map.of("control_plane", avail);
        List<SloService.SloResult> results = slo.evaluate(latency, availMap, Map.of());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("slo", results.stream().map(r -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("name", r.name()); m.put("target", r.target()); m.put("actual", r.actual()); m.put("breached", r.breached()); m.put("runbook", r.runbook());
            try {
                long actualMs = Long.parseLong(r.actual().replace("ms","").trim());
                long targetMs = Long.parseLong(r.target().replace("ms","").trim());
                long headroom = targetMs - (long)(actualMs*1.3);
                m.put("headroom_ms", headroom);
                m.put("headroom_ok", headroom >= 0);
            } catch (Exception ignored) {}
            return m;
        }).toList());
        body.put("all_green", slo.allGreen(results));
        body.put("source", total == 0 ? availSource : "real_metrics");
        body.put("avail_source", availSource);
        body.put("p95_http_ms", httpP95);
        body.put("p99_http_ms", httpP99);
        return new ApiRoutes.Response(200, body);
    }
}
