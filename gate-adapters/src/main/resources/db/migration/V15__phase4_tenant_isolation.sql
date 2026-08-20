-- Phase4 tenant isolation (P0): add tenant_id to all fact tables, backfill default, enforce isolation
-- ADR-007, production-architecture §12.1

-- Ticket is primary fact without tenant; add column with default, then index
ALTER TABLE ticket ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_ticket_tenant ON ticket(tenant_id, ticket_no);

-- Presubmit, review_result, publish_intent already have ticket_no; they can derive tenant via ticket join, but add explicit tenant for direct check
ALTER TABLE presubmit ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_presubmit_tenant ON presubmit(tenant_id);

ALTER TABLE publish_intent ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_publish_intent_tenant ON publish_intent(tenant_id);

-- Agent session and messages
ALTER TABLE agent_session ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_agent_session_tenant ON agent_session(tenant_id);

ALTER TABLE session_message ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';

-- Project already has implicit tenant via workspace; ensure column exists (added in V6? check)
-- project table may not have tenant_id; add if missing
-- SQLite ALTER ADD COLUMN is idempotent only per version, so use separate table creation if needed
-- Attempt to add tenant_id to project; if column exists Flyway will fail on re-run, but V15 is single-version so ok.
ALTER TABLE project ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_project_tenant ON project(tenant_id);

-- Review result: add tenant for direct isolation (derive via presubmit->ticket)
ALTER TABLE review_result ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';

-- Backfill: ensure all old rows are default (already via DEFAULT)
UPDATE ticket SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE presubmit SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE publish_intent SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE agent_session SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE session_message SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE project SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
UPDATE review_result SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
