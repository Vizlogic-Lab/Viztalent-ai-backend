-- F7: the candidate attempt model from the design. One attempt per invite AND
-- level (combined invites are served level by level), answers keep a snapshot
-- of the question as served, every code run is recorded, and integrity events
-- (tab switches, pastes, focus loss, fullscreen exit) are stored per attempt.

-- ── attempts: one per (invite, level) ────────────────────────────────────
ALTER TABLE assessment_attempts ADD COLUMN level VARCHAR(8);
UPDATE assessment_attempts SET level = COALESCE(levels ->> 0, 'L1');
ALTER TABLE assessment_attempts ALTER COLUMN level SET NOT NULL;

ALTER TABLE assessment_attempts DROP CONSTRAINT assessment_attempts_invite_id_key;
ALTER TABLE assessment_attempts ADD CONSTRAINT uq_assessment_attempts_invite_level UNIQUE (invite_id, level);
ALTER TABLE assessment_attempts DROP COLUMN levels;
ALTER TABLE assessment_attempts DROP COLUMN combined;

ALTER TABLE assessment_attempts RENAME COLUMN deadline TO deadline_at;

ALTER TABLE assessment_attempts
    ADD COLUMN version        INT,
    ADD COLUMN resume_id      UUID REFERENCES resumes (id) ON DELETE SET NULL,
    ADD COLUMN theory_score   NUMERIC(5, 2),
    ADD COLUMN hands_on_score NUMERIC(5, 2),
    ADD COLUMN overall_score  NUMERIC(5, 2),
    ADD COLUMN recommendation VARCHAR(24),
    ADD COLUMN flags          JSONB NOT NULL DEFAULT '[]';
UPDATE assessment_attempts t SET version = a.version FROM assessments a WHERE a.id = t.assessment_id;
ALTER TABLE assessment_attempts ALTER COLUMN version SET NOT NULL;

ALTER TABLE assessment_attempts DROP CONSTRAINT assessment_attempts_status_check;
ALTER TABLE assessment_attempts ADD CONSTRAINT ck_assessment_attempts_status CHECK (status IN
    ('IN_PROGRESS', 'SUBMITTED', 'EVALUATING', 'EVALUATED', 'AWAITING_REVIEW', 'VALIDATED', 'EXPIRED'));

-- The auto-submit scheduler scans this every minute.
CREATE INDEX ix_assessment_attempts_overdue ON assessment_attempts (deadline_at) WHERE status = 'IN_PROGRESS';

-- ── invites link to the screened resume when the email matches ──────────
ALTER TABLE invites ADD COLUMN resume_id UUID REFERENCES resumes (id) ON DELETE SET NULL;

-- ── answers ─────────────────────────────────────────────────────────────
ALTER TABLE assessment_answers RENAME TO attempt_answers;
ALTER TABLE attempt_answers RENAME COLUMN code_language TO language;
ALTER TABLE attempt_answers RENAME CONSTRAINT uq_assessment_answers TO uq_attempt_answers;
ALTER TABLE attempt_answers
    -- The question exactly as the candidate was shown it.
    ADD COLUMN question_snapshot JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN auto_score        NUMERIC(8, 2),
    ADD COLUMN rubric_score      JSONB,
    ADD COLUMN final_score       NUMERIC(8, 2),
    ADD COLUMN evidence          JSONB NOT NULL DEFAULT '[]';

-- ── every code execution (candidate "Run" and evaluation "Submit") ──────
CREATE TABLE code_runs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    answer_id     UUID        NOT NULL REFERENCES attempt_answers (id) ON DELETE CASCADE,
    mode          VARCHAR(8)  NOT NULL CHECK (mode IN ('RUN', 'SUBMIT')),
    language      VARCHAR(32),
    results       JSONB       NOT NULL DEFAULT '[]',
    compile_error TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_code_runs_answer_mode ON code_runs (answer_id, mode);

-- ── integrity events (never the pasted content itself) ─────────────────
CREATE TABLE attempt_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID        NOT NULL REFERENCES assessment_attempts (id) ON DELETE CASCADE,
    type       VARCHAR(24) NOT NULL CHECK (type IN ('TAB_SWITCH', 'PASTE', 'FOCUS_LOST', 'FULLSCREEN_EXIT')),
    payload    JSONB       NOT NULL DEFAULT '{}',
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_attempt_events_attempt ON attempt_events (attempt_id);
