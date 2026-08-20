package gate.adapters.metrics;

import gate.ports.metrics.TracingPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * No-op tracing with trace context generation for local. Logs carry trace_id.
 */
public final class NoopTracing implements TracingPort {

    private final ThreadLocal<String> trace = new ThreadLocal<>();

    @Override public Span start(String name, Map<String, String> attrs) {
        String tid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        trace.set(tid);
        return new Span(tid, UUID.randomUUID().toString().substring(0,8), null, name, attrs);
    }
    @Override public void end(Span span) { }
    @Override public Optional<String> currentTraceId() { return Optional.ofNullable(trace.get()); }
}
