# Alert Runbook (production-architecture §13.4)

## stale_fence

- Metric: `gate_task_stale_rejections_total` >10/min
- Cause: lease expiry storm, worker pause
- Action: check `lease_until`, reaper logs, scale Worker, verify fencing

## lease_expiry

- Metric: `gate_task_lease_expiry_total` rate >5%
- Action: check Worker heartbeat, DB replication lag

## git_cas

- Metric: `gate_git_cas_conflict_total` spike
- Cause: concurrent publish on same ref
- Action: inspect `publish_intent` UNKNOWN, run reconcile

## audit_checkpoint

- Metric: `gate_audit_checkpoint_failure_total` >0
- Cause: chain broken or KMS unavailable
- Action: `gate audit verify`, pause writes, re-sign checkpoint

## sse_slow_consumer

- Metric: `gate_sse_dropped_events_total` growth
- Action: client replay via Last-Event-ID, increase `MAX_BUFFERED_EVENTS`

## backup_failure

- Metric: `gate_backup_failure_total`
- Action: check S3/GIT bundle, re-run `BackupService.backup()`, verify manifest
