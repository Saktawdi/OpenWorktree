package gate.adapters.metrics;

import gate.ports.metrics.MetricsPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * In-memory metrics for local/team. Exposes Prometheus text on /metrics.
 * Enterprise wires OTel Prometheus exporter; local keeps same metric names.
 */
public final class InMemoryMetrics implements MetricsPort {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleAdder> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> histP95 = new ConcurrentHashMap<>();

    @Override
    public void counter(String name, long delta, Map<String, String> labels) {
        String key = name + labels;
        counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
        // also aggregate without labels
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public void histogram(String name, long valueMs, Map<String, String> labels) {
        String key = name + labels;
        // naive p95: keep max as proxy for p99 in local tests
        histP95.computeIfAbsent(name, k -> new AtomicLong()).updateAndGet(prev -> Math.max(prev, valueMs));
        histP95.computeIfAbsent(key, k -> new AtomicLong()).updateAndGet(prev -> Math.max(prev, valueMs));
    }

    @Override
    public void gauge(String name, double value, Map<String, String> labels) {
        gauges.computeIfAbsent(name, k -> new DoubleAdder()).reset();
        gauges.computeIfAbsent(name, k -> new DoubleAdder()).add(value);
    }

    @Override public Map<String, Double> snapshotGauges() { return Map.of(); }
    @Override public Map<String, Long> snapshotCounters() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        counters.forEach((k,v) -> out.put(k, v.get()));
        return out;
    }

    public String prometheusText() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((k,v) -> sb.append("# TYPE ").append(k).append(" counter\n").append(k).append(" ").append(v.get()).append("\n"));
        histP95.forEach((k,v) -> sb.append("# TYPE ").append(k).append(" histogram\n").append(k).append("_p95 ").append(v.get()).append("\n"));
        gauges.forEach((k,v) -> sb.append("# TYPE ").append(k).append(" gauge\n").append(k).append(" ").append(v.sum()).append("\n"));
        return sb.toString();
    }

    public long getCounter(String name) { return counters.getOrDefault(name, new AtomicLong()).get(); }
}
