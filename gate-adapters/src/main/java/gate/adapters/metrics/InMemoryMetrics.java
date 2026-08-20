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
    private final ConcurrentHashMap<String, java.util.List<Long>> histograms = new ConcurrentHashMap<>();

    @Override
    public void counter(String name, long delta, Map<String, String> labels) {
        String key = name + labels;
        counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
        // also aggregate without labels
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    private static final int HISTOGRAM_CAPACITY = 1024; // P2 B: bounded ring buffer to avoid slow leak, keeps GOV-CPLX-001 red line #4

    @Override
    public void histogram(String name, long valueMs, Map<String, String> labels) {
        String key = name + labels;
        var agg = histograms.computeIfAbsent(name, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        synchronized (agg) { agg.add(valueMs); if (agg.size() > HISTOGRAM_CAPACITY) agg.remove(0); }
        var perLabel = histograms.computeIfAbsent(key, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        synchronized (perLabel) { perLabel.add(valueMs); if (perLabel.size() > HISTOGRAM_CAPACITY) perLabel.remove(0); }
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

    private long percentile(String name, double p) {
        var list = histograms.get(name);
        if (list == null || list.isEmpty()) return 0;
        var copy = new java.util.ArrayList<>(list);
        copy.sort(Long::compare);
        int idx = (int) Math.ceil(p * copy.size()) - 1;
        idx = Math.max(0, Math.min(idx, copy.size()-1));
        return copy.get(idx);
    }
    public long getP95(String name) { return percentile(name, 0.95); }
    public long getP99(String name) { return percentile(name, 0.99); }
    // legacy alias for slow migration
    public long getHistogramMax(String name) { return getP99(name); }
    public String prometheusText() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((k,v) -> sb.append("# TYPE ").append(k).append(" counter\n").append(k).append(" ").append(v.get()).append("\n"));
        histograms.forEach((k,list) -> {
            if (!list.isEmpty()) {
                long p95 = percentile(k, 0.95);
                long p99 = percentile(k, 0.99);
                sb.append("# TYPE ").append(k).append(" histogram\n");
                sb.append(k).append("_p95 ").append(p95).append("\n");
                sb.append(k).append("_p99 ").append(p99).append("\n");
            }
        });
        gauges.forEach((k,v) -> sb.append("# TYPE ").append(k).append(" gauge\n").append(k).append(" ").append(v.sum()).append("\n"));
        return sb.toString();
    }

    public long getCounter(String name) { return counters.getOrDefault(name, new AtomicLong()).get(); }
    public Map<String, Long> snapshotHistograms() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        histograms.forEach((k,list) -> out.put(k, (long) percentile(k, 0.95)));
        return out;
    }
}
