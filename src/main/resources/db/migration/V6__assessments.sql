-- Phase 6: assessment generation + invites. One assessment (question set)
-- per job — regenerating replaces it (delete + recreate), same pattern as
-- Phase 3's candidates table.
CREATE TABLE assessments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id     UUID NOT NULL UNIQUE REFERENCES jobs (id) ON DELETE CASCADE,
    source     VARCHAR(16) NOT NULL CHECK (source IN ('AI', 'MIX', 'CUSTOM')),
    ready      BOOLEAN NOT NULL DEFAULT false,
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE assessment_questions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id UUID NOT NULL REFERENCES assessments (id) ON DELETE CASCADE,
    level         VARCHAR(8) NOT NULL,
    type          VARCHAR(16) NOT NULL,
    prompt        TEXT NOT NULL,
    options       JSONB NOT NULL DEFAULT '[]',
    correct_indices JSONB NOT NULL DEFAULT '[]',
    skill         VARCHAR(255),
    difficulty    VARCHAR(16),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_assessment_questions_assessment_id ON assessment_questions (assessment_id);

-- Single-use, expiring candidate-facing links (assessment invites now;
-- interview invites in Phase 7 can reuse this table via `kind`). Only the
-- SHA-256 hash of the raw token is stored — see SMARTSTAFF_BACKEND_DESIGN.md
-- §5: "Store invite token hashes, not raw tokens."
CREATE TABLE invites (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash       VARCHAR(64) NOT NULL UNIQUE,
    kind             VARCHAR(16) NOT NULL CHECK (kind IN ('ASSESSMENT', 'INTERVIEW')),
    job_id           UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    candidate_email  VARCHAR(255) NOT NULL,
    candidate_name   VARCHAR(255),
    levels           JSONB NOT NULL DEFAULT '[]',
    combined         BOOLEAN NOT NULL DEFAULT false,
    expires_at       TIMESTAMPTZ NOT NULL,
    used_at          TIMESTAMPTZ,
    created_by       VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Coalescing lookup: same job+email+levels+combined reuses the existing
-- invite instead of minting a new token every time the modal reopens.
CREATE INDEX ix_invites_lookup ON invites (job_id, candidate_email, combined, expires_at);
