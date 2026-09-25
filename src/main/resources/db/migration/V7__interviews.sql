-- Phase 7: self-service + browser AI interviews (SMARTSTAFF_BACKEND_DESIGN.md §4.5).
-- Twilio phone-call fields (twilio_call_sid) are here now so Phase 8 doesn't need
-- another migration just to add them.

CREATE TABLE interviews (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    candidate_id     UUID REFERENCES candidates(id) ON DELETE SET NULL,
    mode             VARCHAR(16) NOT NULL CHECK (mode IN ('SELF', 'BROWSER', 'PHONE')),
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    language         VARCHAR(16) NOT NULL DEFAULT 'en-IN',
    role_title       VARCHAR(255),
    candidate_name   VARCHAR(255),
    phone            VARCHAR(32),
    intro            TEXT,
    outro            TEXT,
    twilio_call_sid  VARCHAR(64),
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_interviews_job ON interviews (job_id, created_at DESC);

CREATE TABLE interview_turns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_id  UUID NOT NULL REFERENCES interviews(id) ON DELETE CASCADE,
    seq           INT NOT NULL,
    category      VARCHAR(64),
    skill         VARCHAR(128),
    question      TEXT NOT NULL,
    answer        TEXT NOT NULL DEFAULT ''
);

CREATE INDEX ix_interview_turns_interview ON interview_turns (interview_id, seq);

-- Self-service interview invites reuse the invites table from V6 (kind='INTERVIEW')
-- but need to point at the specific interview whose pre-generated questions
-- /api/interview/by_token should serve.
ALTER TABLE invites ADD COLUMN interview_id UUID REFERENCES interviews(id) ON DELETE CASCADE;
