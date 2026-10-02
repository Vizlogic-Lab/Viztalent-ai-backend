-- F15: calibration (engine vs SME) and question-quality analytics.

-- The engine's own per-question score and verdict. Sign-off overwrites
-- final_score / recommendation with the SME's, and a re-sign-off after a reopen
-- replaces the review's adjustment list, so the engine's view is kept here.
ALTER TABLE attempt_answers ADD COLUMN engine_score NUMERIC(8, 2);
-- Optional per-criterion SME scores given with an adjustment: {criterion: score}.
ALTER TABLE attempt_answers ADD COLUMN sme_criteria JSONB;
ALTER TABLE assessment_attempts ADD COLUMN engine_recommendation VARCHAR(24);

UPDATE attempt_answers SET engine_score = final_score WHERE sme_score IS NULL;
UPDATE attempt_answers a
   SET engine_score = (adj ->> 'auto_score')::numeric
  FROM sme_reviews r, jsonb_array_elements(r.adjustments) adj
 WHERE r.subject_type = 'ATTEMPT'
   AND r.subject_id = a.attempt_id
   AND adj ->> 'question_id' = a.question_id::text
   AND jsonb_typeof(adj -> 'auto_score') = 'number'
   AND a.sme_score IS NOT NULL;

UPDATE assessment_attempts SET engine_recommendation = recommendation WHERE recommendation IS NOT NULL;
UPDATE assessment_attempts t
   SET engine_recommendation = first_sign_off.rec
  FROM (SELECT DISTINCT ON (r.subject_id) r.subject_id, au.before ->> 'recommendation' AS rec
          FROM review_audit au
          JOIN sme_reviews r ON r.id = au.review_id
         WHERE r.subject_type = 'ATTEMPT' AND au.action = 'SIGNED_OFF'
         ORDER BY r.subject_id, au.at, au.id) first_sign_off
 WHERE t.id = first_sign_off.subject_id
   AND first_sign_off.rec IS NOT NULL;

-- Bank questions auto-flagged too easy / too hard / low discrimination are kept
-- out of generation until an admin re-enables them; stats after a re-enable
-- start a fresh window.
ALTER TABLE question_bank_items
    ADD COLUMN quality_flags        JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN quality_excluded     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN quality_checked_at   TIMESTAMPTZ,
    ADD COLUMN quality_reenabled_at TIMESTAMPTZ,
    ADD COLUMN quality_reenabled_by VARCHAR(255);

-- A calibration set (SME-graded answers) and how the engine scored it at the time.
CREATE TABLE calibration_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_by  VARCHAR(255),
    status      VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    items       JSONB NOT NULL,
    results     JSONB NOT NULL DEFAULT '[]',
    summary     JSONB,
    error       TEXT,
    source_run  UUID REFERENCES calibration_runs (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX ix_calibration_runs_created ON calibration_runs (created_at DESC);
