-- Automatic scoring (F9): one scorecard per attempt, one score row per answered
-- question. Deterministic parts (MCQ/MSQ/LOGIC/CODE_OUTPUT and the test runs of
-- the coding types) are always scored; the AI parts (CODE_WRITE rubric quality,
-- SCENARIO key-point coverage) are graded by Gemini, and flagged for manual
-- review when Gemini is unavailable.

CREATE TABLE assessment_scorecards (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id     UUID          NOT NULL UNIQUE REFERENCES assessment_attempts (id) ON DELETE CASCADE,
    job_id         UUID          NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    assessment_id  UUID          NOT NULL REFERENCES assessments (id) ON DELETE CASCADE,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'SCORING', 'SCORED', 'FAILED')),
    total_score    NUMERIC(8, 2) NOT NULL DEFAULT 0,
    max_score      NUMERIC(8, 2) NOT NULL DEFAULT 0,
    percent        NUMERIC(5, 2) NOT NULL DEFAULT 0,
    pass_threshold INT           NOT NULL DEFAULT 50,
    passed         BOOLEAN       NOT NULL DEFAULT false,
    -- points that couldn't be auto-graded (Gemini down) and need a human.
    needs_review   BOOLEAN       NOT NULL DEFAULT false,
    review_points  NUMERIC(8, 2) NOT NULL DEFAULT 0,
    error          TEXT,
    scored_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_assessment_scorecards_job ON assessment_scorecards (job_id);

CREATE TABLE assessment_question_scores (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scorecard_id UUID          NOT NULL REFERENCES assessment_scorecards (id) ON DELETE CASCADE,
    question_id  UUID          NOT NULL REFERENCES assessment_questions (id) ON DELETE CASCADE,
    level        VARCHAR(8)    NOT NULL,
    seq          INT           NOT NULL,
    type         VARCHAR(16)   NOT NULL,
    max_points   INT           NOT NULL,
    score        NUMERIC(8, 2) NOT NULL DEFAULT 0,
    auto_graded  BOOLEAN       NOT NULL DEFAULT true,
    needs_review BOOLEAN       NOT NULL DEFAULT false,
    answered     BOOLEAN       NOT NULL DEFAULT false,
    tests_passed INT,
    tests_total  INT,
    -- AI rubric breakdown (CODE_WRITE) or key-point coverage (SCENARIO).
    breakdown    JSONB         NOT NULL DEFAULT '[]',
    detail       TEXT,
    CONSTRAINT uq_assessment_question_scores UNIQUE (scorecard_id, question_id)
);
