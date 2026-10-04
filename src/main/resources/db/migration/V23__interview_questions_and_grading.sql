-- F16: recruiter interview questions, AI grading of L1 interviews, interview
-- results in the Theory/Hands-on model and the SME review, phone-silence fix.

CREATE TABLE job_interview_questions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                 UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    seq                    INT NOT NULL,
    question               TEXT NOT NULL,
    category               VARCHAR(64),
    skill                  VARCHAR(128),
    expected_points        JSONB NOT NULL DEFAULT '[]',
    -- Who wrote the expected points: HR, or AI (filled in when HR left them empty).
    expected_points_source VARCHAR(8) CHECK (expected_points_source IN ('HR', 'AI')),
    dimension              VARCHAR(16) NOT NULL DEFAULT 'THEORY' CHECK (dimension IN ('THEORY', 'HANDS_ON')),
    created_by             VARCHAR(255),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_job_interview_questions_job ON job_interview_questions (job_id, seq);

-- Questions per interview (recruiter + AI); NULL = 5.
ALTER TABLE jobs ADD COLUMN interview_question_total INT CHECK (interview_question_total BETWEEN 1 AND 10);

-- score/max_score are the engine's; sme_score (when set) is the SME's and wins.
ALTER TABLE interview_turns
    ADD COLUMN source            VARCHAR(16) NOT NULL DEFAULT 'AI' CHECK (source IN ('RECRUITER', 'AI')),
    ADD COLUMN dimension         VARCHAR(16) NOT NULL DEFAULT 'THEORY' CHECK (dimension IN ('THEORY', 'HANDS_ON')),
    ADD COLUMN expected_points   JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN score             NUMERIC(6, 2),
    ADD COLUMN max_score         NUMERIC(6, 2),
    ADD COLUMN evidence          JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN rubric_runs       JSONB,
    ADD COLUMN flags             JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN sme_score         NUMERIC(6, 2),
    ADD COLUMN sme_reason        TEXT,
    ADD COLUMN speech_confidence NUMERIC(5, 4);

-- interviews.status stays the call's state (PENDING/COMPLETED/FAILED); grading has its own.
ALTER TABLE interviews
    ADD COLUMN evaluation_status     VARCHAR(16)
        CHECK (evaluation_status IN ('EVALUATING', 'EVALUATED', 'AWAITING_REVIEW', 'VALIDATED')),
    ADD COLUMN theory_score          NUMERIC(5, 2),
    ADD COLUMN hands_on_score        NUMERIC(5, 2),
    ADD COLUMN overall_score         NUMERIC(5, 2),
    ADD COLUMN theory_band           VARCHAR(20),
    ADD COLUMN hands_on_band         VARCHAR(20),
    ADD COLUMN recommendation        VARCHAR(24),
    ADD COLUMN engine_recommendation VARCHAR(24),
    ADD COLUMN flags                 JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN validation_reason     VARCHAR(32),
    ADD COLUMN evaluation_started_at TIMESTAMPTZ,
    ADD COLUMN evaluated_at          TIMESTAMPTZ,
    ADD COLUMN rubric_pending_since  TIMESTAMPTZ,
    ADD COLUMN next_evaluation_at    TIMESTAMPTZ,
    ADD COLUMN evaluation_tries      INT NOT NULL DEFAULT 0;
CREATE INDEX ix_interviews_evaluation_due ON interviews (next_evaluation_at) WHERE evaluation_status = 'EVALUATING';

ALTER TABLE sme_reviews DROP CONSTRAINT sme_reviews_subject_type_check;
ALTER TABLE sme_reviews ADD CONSTRAINT sme_reviews_subject_type_check
    CHECK (subject_type IN ('QUESTION_SET', 'ATTEMPT', 'INTERVIEW'));
