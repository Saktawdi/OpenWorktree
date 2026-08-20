-- Phase4 SoD: record reviewer user and enforce dual-approval exception
-- ADR-007 SoD, production-architecture §12.1

ALTER TABLE review_result ADD COLUMN reviewer_user_id TEXT;
CREATE INDEX IF NOT EXISTS ix_review_result_reviewer ON review_result(reviewer_user_id);

-- Ensure sod_exception has index for fast lookup
CREATE INDEX IF NOT EXISTS ix_sod_exception_ticket_round ON sod_exception(ticket_no, review_round);

-- Optional: track publish actor for SoD as well
ALTER TABLE publish_intent ADD COLUMN publisher_user_id TEXT;
