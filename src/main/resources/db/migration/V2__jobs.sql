-- Phase 2: jobs module — JD upload, skill extraction, resume storage.
-- Sequence backs the human-facing "JD-0001" display number (monotonic,
-- never reused even after eviction/deletion).
CREATE SEQUENCE jd_number_seq START WITH 1;

CREATE TABLE jobs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jd_number             BIGINT NOT NULL DEFAULT nextval('jd_number_seq'),
    title                 VARCHAR(255) NOT NULL,
    original_filename     VARCHAR(255),
    file_path             TEXT,
    jd_text               TEXT,
    content_hash          VARCHAR(64),
    must_have_skills      JSONB NOT NULL DEFAULT '[]',
    nice_to_have_skills   JSONB NOT NULL DEFAULT '[]',
    experience_min_years  INT,
    experience_max_years  INT,
    owner_id              VARCHAR(255) NOT NULL,
    owner_name            VARCHAR(255),
    owner_email           VARCHAR(255),
    owner_role            VARCHAR(16),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_jobs_owner_id ON jobs (owner_id);
CREATE INDEX ix_jobs_content_hash ON jobs (owner_id, content_hash);
CREATE INDEX ix_jobs_created_at ON jobs (created_at);

CREATE TABLE resumes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id         UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    filename       VARCHAR(255) NOT NULL,
    file_path      TEXT NOT NULL,
    extracted_text TEXT,
    size_bytes     BIGINT NOT NULL DEFAULT 0,
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_resumes_job_id ON resumes (job_id);
