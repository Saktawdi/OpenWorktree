package gate.ports.metrics;

import java.util.Map;
import java.util.Optional;

/**
 * Minimal OTel tracing port. Local: no-op with context propagation; enterprise: OTel SDK.
 * Every log/span must carry trace_id, request_id, tenant_id, task_id per §13.1.
 */
public interface TracingPort {

    record Span(String traceId, String spanId, String parentId, String name, Map<String, String> attrs) {}

    Span start(String name, Map<String, String> attrs);
    void end(Span span);
    Optional<String> currentTraceId();
}
