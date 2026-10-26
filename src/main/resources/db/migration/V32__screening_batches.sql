-- Batch link: one shared link per (job, assessment) for a screened group of candidates.
-- The roster is a snapshot taken when the batch is created, so re-running screening
-- afterwards doesn't change who is on it. Candidates prove their email with a one-time
-- code on the shared link and then get a normal per-candidate invite (no AI call).
CREATE TABLE screening_batches (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id        UUID         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    assessment_id UUID         NOT NULL REFERENCES assessments (id) ON DELETE CASCADE,
    token         VARCHAR(64)  NOT NULL UNIQUE,
    levels        JSONB        NOT NULL DEFAULT '[]',
    min_score     INT          NOT NULL DEFAULT 50,
    expires_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_screening_batches_job ON screening_batches (job_id);

CREATE TABLE batch_candidates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id        UUID         NOT NULL REFERENCES screening_batches (id) ON DELETE CASCADE,
    candidate_name  VARCHAR(255),
    email           VARCHAR(320) NOT NULL,
    resume_id       UUID,
    fit_score       INT          NOT NULL,
    included        BOOLEAN      NOT NULL,
    otp_hash        VARCHAR(64),
    otp_expires_at  TIMESTAMPTZ,
    otp_sent_at     TIMESTAMPTZ,
    otp_attempts    INT          NOT NULL DEFAULT 0,
    otp_sends       INT          NOT NULL DEFAULT 0,
    invite_id       UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_batch_candidates_email ON batch_candidates (batch_id, lower(email));
