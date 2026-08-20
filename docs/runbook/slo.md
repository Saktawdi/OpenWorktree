# SLO Runbook

Owner: Platform owner  
Source: production-architecture §14

## Targets

| SLO | target | runbook |
|---|---|---|
| short_read_p95 | 200ms | §short_read |
| short_write_p95 | 300ms | §short_write |
| sse_visible_p95 | 2s | §sse |
| availability | 99.9% | §availability |

## Short Read

- Symptom: p95 >200ms
- Check: DB pool, slow SQL, lock wait
- Mitigate: scale Web, drain Worker, check `gate_http_request_duration_ms` histogram

## Short Write

- Symptom: p95 >300ms
- Check: task enqueue contention, idempotency index
- Mitigate: increase DB connections, check `gate_task_queue_depth`

## SSE

- Symptom: `sse_visible_p95` >2s
- Check: `gate_sse_connections`, `gate_sse_dropped_events_total`
- Mitigate: backpressure drop-oldest, client Last-Event-ID replay

## Availability

- 99.9% monthly: `gate_http_requests_total` 5xx ratio
- Alert fires when 1m error rate >0.1%
