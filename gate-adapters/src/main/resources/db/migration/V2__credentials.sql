-- Flyway V2: MCP two-domain credentials (P3).
--
-- The MCP stdio server (架构落地执行文档 §5.4, §11.3) enforces a server-side domain check: agent
-- domain tokens can only reach agent-domain tools; human/orchestrator tokens reach everything.
-- review_run and commit_and_publish are never exposed to the agent domain.
--
-- Only the SHA-256 hash of a token is stored (§6.1 / ADR-9: the plaintext is returned once at
-- issuance, never persisted — mirrors api_key handling). The token arrives via environment variable
-- (GATE_DOMAIN_TOKEN), never argv.

CREATE TABLE credential (
  id            INTEGER PRIMARY KEY,
  token_hash    TEXT NOT NULL UNIQUE,    -- SHA-256 hex of the plaintext token
  domain        TEXT NOT NULL,           -- AGENT | HUMAN
  ticket_no     TEXT,                    -- agent domain: the bound ticket; human domain: NULL
  created_at    TEXT NOT NULL,
  revoked_at    TEXT                      -- non-NULL => revoked
);

CREATE INDEX idx_credential_token_hash ON credential(token_hash);
