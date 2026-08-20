package gate.adapters.metrics;

import gate.ports.metrics.MetricsPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase4 alert evaluation (production-architecture §13.4, runbook/alerts.md).
 * Local: DB alert_rule + InMemoryMetrics snapshot. Enterprise: Prometheus Alertmanager.
 */
public final class AlertService {

    private final MetricsPort metrics;
    private final JdbcTemplate jdbc;

    public AlertService(MetricsPort metrics, JdbcTemplate jdbc) {
        this.metrics = metrics; this.jdbc = jdbc;
    }

    public record Alert(String ruleId, String metric, double threshold, double actual, String runbook, boolean firing) {}

    public List<Alert> evaluate() {
        List<Alert> out = new ArrayList<>();
        Map<String, Long> counters = metrics.snapshotCounters();
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT rule_id, metric_name, threshold, runbook FROM alert_rule WHERE enabled=1");
        for (Map<String, Object> r : rules) {
            String ruleId = String.valueOf(r.get("rule_id"));
            String metric = String.valueOf(r.get("metric_name"));
            double threshold = ((Number) r.get("threshold")).doubleValue();
            String runbook = String.valueOf(r.get("runbook"));
            long actual = 0;
            for (Map.Entry<String, Long> e : counters.entrySet()) {
                if (e.getKey().equals(metric) || e.getKey().startsWith(metric)) actual = Math.max(actual, e.getValue());
            }
            boolean firing = actual >= threshold;
            out.add(new Alert(ruleId, metric, threshold, actual, runbook, firing));
            if (firing) metrics.counter("gate_alert_firing_total", 1, Map.of("rule", ruleId));
        }
        return out;
    }

    public List<Alert> firing() {
        return evaluate().stream().filter(Alert::firing).toList();
    }
}
