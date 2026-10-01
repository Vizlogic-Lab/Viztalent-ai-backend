-- F9: evaluation engine state. Per-question flags (HARDCODED,
-- GRADER_DISAGREEMENT, RUBRIC_PENDING, RUBRIC_UNAVAILABLE) live on the answer;
-- the attempt keeps the timing needed for the retry scheduler: attempts stuck
-- in SUBMITTED are picked up after 5 minutes, and a rubric that couldn't be
-- graded (Gemini down) is retried with backoff for up to an hour.

ALTER TABLE attempt_answers ADD COLUMN flags JSONB NOT NULL DEFAULT '[]';

ALTER TABLE assessment_attempts
    ADD COLUMN evaluation_started_at TIMESTAMPTZ,
    ADD COLUMN evaluated_at          TIMESTAMPTZ,
    ADD COLUMN rubric_pending_since  TIMESTAMPTZ,
    ADD COLUMN next_evaluation_at    TIMESTAMPTZ,
    ADD COLUMN evaluation_tries      INT NOT NULL DEFAULT 0;

CREATE INDEX ix_assessment_attempts_eval_due ON assessment_attempts (next_evaluation_at)
    WHERE status = 'EVALUATING';
