-- Practical assessments (F2): versioned assessments, practical question
-- columns, and per-question test cases.

-- ── assessments: one row per version instead of one per job ────────────
ALTER TABLE assessments
    ADD COLUMN version      INT         NOT NULL DEFAULT 1,
    ADD COLUMN status       VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN blueprint    JSONB,
    ADD COLUMN generated_at TIMESTAMPTZ,
    ADD COLUMN is_current   BOOLEAN     NOT NULL DEFAULT true;

UPDATE assessments SET status = CASE WHEN ready THEN 'READY' ELSE 'FAILED' END;
UPDATE assessments SET generated_at = created_at WHERE ready;

ALTER TABLE assessments
    DROP COLUMN ready,
    ADD CONSTRAINT ck_assessments_status
        CHECK (status IN ('QUEUED', 'GENERATING', 'VALIDATING', 'READY', 'FAILED'));

ALTER TABLE assessments DROP CONSTRAINT assessments_job_id_key;
ALTER TABLE assessments ADD CONSTRAINT uq_assessments_job_version UNIQUE (job_id, version);
CREATE UNIQUE INDEX ux_assessments_current_per_job ON assessments (job_id) WHERE is_current;

-- ── assessment_questions: practical fields ─────────────────────────────
ALTER TABLE assessment_questions
    ADD COLUMN seq                INT         NOT NULL DEFAULT 0,
    ADD COLUMN dimension          VARCHAR(16),
    ADD COLUMN competency         VARCHAR(16),
    ADD COLUMN points             INT         NOT NULL DEFAULT 5,
    ADD COLUMN time_estimate_sec  INT,
    ADD COLUMN languages          JSONB       NOT NULL DEFAULT '[]',
    ADD COLUMN starter_code       JSONB       NOT NULL DEFAULT '{}',
    ADD COLUMN reference_solution JSONB       NOT NULL DEFAULT '{}',
    ADD COLUMN buggy_code         JSONB       NOT NULL DEFAULT '{}',
    ADD COLUMN model_answer       TEXT,
    ADD COLUMN key_points         JSONB       NOT NULL DEFAULT '[]',
    ADD COLUMN rubric             JSONB       NOT NULL DEFAULT '[]',
    ADD COLUMN validated          BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN validation_log     TEXT;

-- The bank's legacy types map onto the new ones. Legacy coding questions have
-- no reference solution or tests, so they stay unvalidated.
UPDATE assessment_questions SET type = 'SCENARIO'   WHERE type = 'DESCRIPTIVE';
UPDATE assessment_questions SET type = 'CODE_WRITE' WHERE type = 'CODING';

UPDATE assessment_questions SET
    dimension  = CASE WHEN type IN ('MCQ', 'MSQ') THEN 'THEORY' ELSE 'HANDS_ON' END,
    competency = CASE type
                     WHEN 'MCQ'         THEN 'CONCEPTS'
                     WHEN 'MSQ'         THEN 'CONCEPTS'
                     WHEN 'CODE_WRITE'  THEN 'CODING'
                     WHEN 'CODE_DEBUG'  THEN 'DEBUGGING'
                     WHEN 'CODE_OUTPUT' THEN 'CODE_READING'
                     WHEN 'LOGIC'       THEN 'LOGIC'
                     ELSE 'APPLICATION'
                 END,
    validated  = type IN ('MCQ', 'MSQ');

-- Keep the legacy per-level order stable: number questions by creation time.
UPDATE assessment_questions q SET seq = n.rn
FROM (SELECT id, row_number() OVER (PARTITION BY assessment_id, level ORDER BY created_at, id) - 1 AS rn
      FROM assessment_questions) n
WHERE q.id = n.id;

ALTER TABLE assessment_questions
    ALTER COLUMN dimension SET NOT NULL,
    ALTER COLUMN competency SET NOT NULL,
    ALTER COLUMN type TYPE VARCHAR(16),
    ADD CONSTRAINT ck_assessment_questions_type
        CHECK (type IN ('MCQ', 'MSQ', 'CODE_WRITE', 'CODE_DEBUG', 'CODE_OUTPUT', 'SCENARIO', 'LOGIC')),
    ADD CONSTRAINT ck_assessment_questions_dimension CHECK (dimension IN ('THEORY', 'HANDS_ON')),
    ADD CONSTRAINT ck_assessment_questions_competency
        CHECK (competency IN ('CONCEPTS', 'CODING', 'DEBUGGING', 'CODE_READING', 'LOGIC', 'APPLICATION')),
    ADD CONSTRAINT ck_assessment_questions_points CHECK (points >= 0);

-- ── test cases (hidden ones never leave the server) ────────────────────
CREATE TABLE question_test_cases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id     UUID          NOT NULL REFERENCES assessment_questions (id) ON DELETE CASCADE,
    seq             INT           NOT NULL,
    input           TEXT          NOT NULL DEFAULT '',
    expected_output TEXT          NOT NULL,
    visible         BOOLEAN       NOT NULL DEFAULT false,
    category        VARCHAR(8)    NOT NULL CHECK (category IN ('BASIC', 'EDGE', 'LARGE')),
    weight          NUMERIC(6, 2) NOT NULL DEFAULT 1 CHECK (weight > 0),
    float_tolerance NUMERIC       CHECK (float_tolerance IS NULL OR float_tolerance >= 0),
    CONSTRAINT uq_question_test_cases_seq UNIQUE (question_id, seq)
);
