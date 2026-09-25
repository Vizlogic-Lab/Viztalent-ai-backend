-- Phase 3: screening — deterministic candidate scoring per resume.
-- Re-running screening for a job deletes and recreates every row here
-- (see ScreeningServiceImpl.runScreening), so this table is a computed
-- cache, not a source of truth on its own.
CREATE TABLE candidates (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    resume_id             UUID NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    candidate_name        VARCHAR(255),
    email                 VARCHAR(255),
    phone                 VARCHAR(64),
    years_experience      INT NOT NULL DEFAULT 0,
    fit_score             INT NOT NULL DEFAULT 0,
    matched_skills        JSONB NOT NULL DEFAULT '[]',
    missing_skills        JSONB NOT NULL DEFAULT '[]',
    matched_required_count INT NOT NULL DEFAULT 0,
    total_required_count   INT NOT NULL DEFAULT 0,
    score_breakdown       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_candidates_job_id ON candidates (job_id);
CREATE UNIQUE INDEX ux_candidates_resume_id ON candidates (resume_id);
