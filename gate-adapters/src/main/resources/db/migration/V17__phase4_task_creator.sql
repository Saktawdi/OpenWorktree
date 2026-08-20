-- Phase4 SoD: track task creator for worker context propagation
ALTER TABLE gate_task ADD COLUMN created_by TEXT;
CREATE INDEX IF NOT EXISTS ix_gate_task_created_by ON gate_task(created_by);
