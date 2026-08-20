-- Phase4: RBAC, SoD, tenancy, WORM audit (ADR-007, production-architecture §12)
-- Expand: add tenant-scoped RBAC and immutable checkpoint tables; no breaking change to existing credential.

-- 1. RBAC: user-role binding (tenant+project scoped, revokable)
CREATE TABLE IF NOT EXISTS gate_user_role (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL DEFAULT 'default',
    project_id TEXT,
    role TEXT NOT NULL,
    granted_by TEXT,
    granted_at TEXT NOT NULL,
    revoked_at TEXT,
    UNIQUE(user_id, tenant_id, project_id, role)
);
CREATE INDEX IF NOT EXISTS ix_user_role_tenant ON gate_user_role(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS ix_user_role_project ON gate_user_role(project_id);

-- 2. Tenant registry (for expand / backfill)
CREATE TABLE IF NOT EXISTS gate_tenant (
    tenant_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
);
INSERT OR IGNORE INTO gate_tenant(tenant_id, display_name, created_at) VALUES ('default', 'default tenant', datetime('now'));

-- 3. WORM audit checkpoint (KMS-signed)
CREATE TABLE IF NOT EXISTS audit_checkpoint (
    checkpoint_id TEXT PRIMARY KEY,
    prev_checkpoint_hash TEXT NOT NULL,
    root_hash TEXT NOT NULL,
    kms_key_id TEXT NOT NULL,
    signature TEXT NOT NULL,
    created_at TEXT NOT NULL,
    event_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_audit_checkpoint_created ON audit_checkpoint(created_at);

-- 4. SoD exception approvals (dual approval for high-risk publish)
CREATE TABLE IF NOT EXISTS sod_exception (
    id TEXT PRIMARY KEY,
    ticket_no TEXT NOT NULL,
    review_round INTEGER NOT NULL,
    requester_user_id TEXT NOT NULL,
    approver_user_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(ticket_no, review_round, requester_user_id)
);

-- 5. Alter credential to carry tenant_id/roles for fast path (nullable for legacy)
-- SQLite ADD COLUMN is idempotent guarded by existence check in migrate; use simple ADD with DEFAULT
-- We use separate ALTER attempts wrapped in try by app if column missing; here we add if not exists via trick: create temp?
-- For SQLite we can safely add; if already exists Flyway will fail so we use IF NOT EXISTS emulation via separate migration contract
-- Simplified: add columns plain; re-running is safe because V13 is single version.
ALTER TABLE credential ADD COLUMN tenant_id TEXT DEFAULT 'default';
ALTER TABLE credential ADD COLUMN roles TEXT;
ALTER TABLE credential ADD COLUMN user_id TEXT;

-- 6. Extend gate_task/ticket with tenant backfill helper index (already have tenant_id)
CREATE INDEX IF NOT EXISTS ix_gate_task_tenant ON gate_task(tenant_id, project_id);
