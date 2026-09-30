-- Candidate assessment-taking flow (F6): one attempt per invite, one answer
-- row per question. Grading (scores) is deferred to F9 — this only captures
-- what the candidate submitted.

CREATE TABLE assessment_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_id       UUID          NOT NULL UNIQUE REFERENCES invites (id) ON DELETE CASCADE,
    assessment_id   UUID          NOT NULL REFERENCES assessments (id) ON DELETE CASCADE,
    job_id          UUID          NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    candidate_email VARCHAR(320)  NOT NULL,
    candidate_name  VARCHAR(255),
    levels          JSONB         NOT NULL DEFAULT '[]',
    combined        BOOLEAN       NOT NULL DEFAULT false,
    status          VARCHAR(16)   NOT NULL DEFAULT 'IN_PROGRESS'
                        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    started_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deadline        TIMESTAMPTZ   NOT NULL,
    submitted_at    TIMESTAMPTZ,
    -- true when the deadline passed before the candidate pressed submit.
    auto_submitted  BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_assessment_attempts_job ON assessment_attempts (job_id);

CREATE TABLE assessment_answers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id       UUID        NOT NULL REFERENCES assessment_attempts (id) ON DELETE CASCADE,
    question_id      UUID        NOT NULL REFERENCES assessment_questions (id) ON DELETE CASCADE,
    -- MCQ/MSQ selections.
    selected_indices JSONB       NOT NULL DEFAULT '[]',
    -- CODE_WRITE / CODE_DEBUG.
    code_language    VARCHAR(32),
    code             TEXT,
    -- SCENARIO / LOGIC / CODE_OUTPUT free text.
    text_answer      TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessment_answers UNIQUE (attempt_id, question_id)
);
