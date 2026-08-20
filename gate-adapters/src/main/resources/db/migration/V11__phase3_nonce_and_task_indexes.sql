-- Phase3: Authoritative Git nonce store + task claim indexes + idempotency unique
-- ADR-002, ADR-003, production-architecture §6, §7

-- 1. Nonce store for git CAS (refs/gate/authorizations/<nonce> local mock)
CREATE TABLE IF NOT EXISTS gate_nonce (
    nonce TEXT PRIMARY KEY,
    ticket_no TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    new_commit_oid TEXT NOT NULL,
    consumed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_gate_nonce_ticket ON gate_nonce(ticket_no);

-- 2. Task claim index: priority DESC, available_at ASC, id ASC for claimNext
CREATE INDEX IF NOT EXISTS ix_gate_task_claim ON gate_task(status, priority, available_at, id);

-- 3. Idempotency unique constraint: tenant + project + type + key
-- Note: SQLite treats NULL as distinct; we enforce via partial index where idempotency_key IS NOT NULL
CREATE UNIQUE INDEX IF NOT EXISTS ux_gate_task_idempotency ON gate_task(tenant_id, project_id, type, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- 4. Lease index for reaper
CREATE INDEX IF NOT EXISTS ix_gate_task_lease ON gate_task(status, lease_until);

-- 5. Ensure gate_task has missing Phase3 columns if V9 not applied (idempotent)
-- (no-op if already exists; SQLite will error on duplicate ADD COLUMN, so guard via existence check is handled by IF NOT EXISTS in app)
