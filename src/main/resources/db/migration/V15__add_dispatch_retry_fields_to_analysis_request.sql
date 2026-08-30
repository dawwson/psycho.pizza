ALTER TABLE analysis_request
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at TIMESTAMP(6),
    ADD COLUMN next_retry_at TIMESTAMP(6);

-- backfill
UPDATE analysis_request
SET attempt_count = 1,
    last_attempt_at = started_at
WHERE status IN ('RUNNING', 'DONE', 'FAILED');
