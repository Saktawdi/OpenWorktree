-- Flyway V3: cost metrics telemetry (P4).
--
-- Bypass-only: metrics recording never blocks the publish/review path (执行文档 §4 P4, §15).
-- All columns are nullable — if token data is unavailable (the common case), the row still exists
-- and the metric basis is marked 'degraded'.
--
-- prism JSON (confirmed in docs/archive/prism-schema-validation.md) exposes timing.gitMs/llmMs/totalMs but NO
-- usage/token field. exec_token_cost depends on the agent CLI emitting usage, which this project
-- does not control (it does not spawn the agent). So:
--   - review_token_cost: usually NULL (prism JSON has no usage); source = 'unavailable'
--   - exec_token_cost: usually NULL (agent CLI token not captured); source = 'unavailable'
--   - review_wall_ms / llm_wall_ms: populated from prism timing.* when available
--   - The H1 verdict therefore falls back to the degraded basis: review_round + diff_size + wall-clock
--     (执行文档 §4 P4 footnote: 'exec_token_cost 常拿不到', explicit degradation required).

ALTER TABLE review_result ADD COLUMN prompt_tokens INTEGER;
ALTER TABLE review_result ADD COLUMN completion_tokens INTEGER;
ALTER TABLE review_result ADD COLUMN total_tokens INTEGER;
ALTER TABLE review_result ADD COLUMN token_source TEXT;        -- engine_json | gateway_usage | unavailable
ALTER TABLE review_result ADD COLUMN review_wall_ms INTEGER;   -- prism timing.totalMs
ALTER TABLE review_result ADD COLUMN llm_wall_ms INTEGER;      -- prism timing.llmMs
ALTER TABLE review_result ADD COLUMN diff_bytes INTEGER;       -- redundant from presubmit for single-table queries
ALTER TABLE review_result ADD COLUMN diff_lines INTEGER;      -- estimated: count of \n in the diff

ALTER TABLE ticket ADD COLUMN exec_token_total INTEGER;       -- cumulative exec-agent tokens (manually reported, usually NULL)
ALTER TABLE ticket ADD COLUMN exec_token_source TEXT;          -- agent_cli | manual | unavailable
