-- Phase4 tenant for agent_config (P1-2)
ALTER TABLE agent_config ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
CREATE INDEX IF NOT EXISTS ix_agent_config_tenant ON agent_config(tenant_id);
UPDATE agent_config SET tenant_id='default' WHERE tenant_id IS NULL OR tenant_id='';
