-- Question bank upgrade (F5): curated practical questions with the same
-- fields as assessment_questions, their test cases, and role families.

ALTER TABLE question_bank_items DROP CONSTRAINT question_bank_items_type_check;
UPDATE question_bank_items SET type = 'SCENARIO'   WHERE type = 'DESCRIPTIVE';
UPDATE question_bank_items SET type = 'CODE_WRITE' WHERE type = 'CODING';
ALTER TABLE question_bank_items ADD CONSTRAINT ck_question_bank_items_type
    CHECK (type IN ('MCQ', 'MSQ', 'CODE_WRITE', 'CODE_DEBUG', 'CODE_OUTPUT', 'SCENARIO', 'LOGIC'));

ALTER TABLE question_bank_items
    ADD COLUMN dimension           VARCHAR(16),
    ADD COLUMN competency          VARCHAR(16),
    ADD COLUMN points              INT,
    ADD COLUMN time_estimate_sec   INT,
    ADD COLUMN title               TEXT,
    ADD COLUMN constraints         TEXT,
    ADD COLUMN input_format        TEXT,
    ADD COLUMN output_format       TEXT,
    ADD COLUMN languages           JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN starter_code        JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN reference_solution  JSONB NOT NULL DEFAULT '{}',
    -- A deliberately flawed solution; proves the hidden tests catch flawed code. Never shown to candidates.
    ADD COLUMN naive_solution      JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN buggy_code          JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN model_answer        TEXT,
    ADD COLUMN key_points          JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN rubric              JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN explanation         TEXT,
    ADD COLUMN expected_complexity VARCHAR(64),
    ADD COLUMN bug_descriptions    JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN role_families       JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN validated           BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN validation_log      TEXT;

-- Legacy choice questions were already structurally checked on upload; legacy
-- coding/descriptive items carry no solution, tests or model answer.
UPDATE question_bank_items SET
    dimension  = CASE WHEN type IN ('MCQ', 'MSQ') THEN 'THEORY' ELSE 'HANDS_ON' END,
    competency = CASE type WHEN 'CODE_WRITE' THEN 'CODING' WHEN 'SCENARIO' THEN 'APPLICATION' ELSE 'CONCEPTS' END,
    validated  = type IN ('MCQ', 'MSQ'),
    difficulty = lower(difficulty);

ALTER TABLE question_bank_items
    ALTER COLUMN dimension SET NOT NULL,
    ALTER COLUMN competency SET NOT NULL;

CREATE INDEX ix_question_bank_items_type_level ON question_bank_items (type, level);

CREATE TABLE question_bank_test_cases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id         UUID          NOT NULL REFERENCES question_bank_items (id) ON DELETE CASCADE,
    seq             INT           NOT NULL,
    input           TEXT          NOT NULL DEFAULT '',
    expected_output TEXT          NOT NULL,
    visible         BOOLEAN       NOT NULL DEFAULT false,
    category        VARCHAR(8)    NOT NULL CHECK (category IN ('BASIC', 'EDGE', 'LARGE')),
    weight          NUMERIC(6, 2) NOT NULL DEFAULT 1 CHECK (weight > 0),
    float_tolerance NUMERIC       CHECK (float_tolerance IS NULL OR float_tolerance >= 0),
    CONSTRAINT uq_question_bank_test_cases_seq UNIQUE (item_id, seq)
);
