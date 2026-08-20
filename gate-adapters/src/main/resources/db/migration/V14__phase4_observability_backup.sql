-- Phase4: Observability, SLO, backup, health (ADR-007/008, production-architecture §13-15)
-- Expand: add metrics/slo/backup tables; no breaking change.

CREATE TABLE IF NOT EXISTS slo_history (
    id TEXT PRIMARY KEY,
    evaluated_at TEXT NOT NULL,
    result_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS backup_manifest (
    backup_id TEXT PRIMARY KEY,
    at TEXT NOT NULL,
    db_sha256 TEXT,
    git_bundle_key TEXT,
    s3_manifest_key TEXT,
    s3_version TEXT,
    status TEXT NOT NULL DEFAULT 'OK'
);

CREATE TABLE IF NOT EXISTS health_probe (
    id INTEGER PRIMARY KEY,
    v TEXT
);

-- Ensure audit_checkpoint exists (from V13) and add index
CREATE INDEX IF NOT EXISTS ix_backup_at ON backup_manifest(at);

-- Add runbook index for alert rules (local)
CREATE TABLE IF NOT EXISTS alert_rule (
    rule_id TEXT PRIMARY KEY,
    metric_name TEXT NOT NULL,
    threshold REAL NOT NULL,
    runbook TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1
);
INSERT OR IGNORE INTO alert_rule(rule_id, metric_name, threshold, runbook) VALUES ('ALERT_STALE_FENCE', 'gate_task_stale_rejections_total', 10, 'runbook/alerts.md#stale_fence');
INSERT OR IGNORE INTO alert_rule(rule_id, metric_name, threshold, runbook) VALUES ('ALERT_LEASE_EXPIRY', 'gate_task_lease_expiry_total', 5, 'runbook/alerts.md#lease_expiry');
INSERT OR IGNORE INTO alert_rule(rule_id, metric_name, threshold, runbook) VALUES ('ALERT_GIT_CAS', 'gate_git_cas_conflict_total', 5, 'runbook/alerts.md#git_cas');

-- Metrics projection already in V10; add indext for slo
CREATE INDEX IF NOT EXISTS ix_ticket_metrics_projection_updated ON ticket_metrics_projection(updated_at);
