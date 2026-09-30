-- Job Role Profiles: AI-extracted or HR-edited skill profiles for each job.
-- Captures role family, technical nature, languages, frameworks, seniority, and skill weights.

CREATE TYPE role_family AS ENUM ('BACKEND', 'FRONTEND', 'FULLSTACK', 'DATA', 'DEVOPS', 'QA', 'MOBILE', 'NON_TECHNICAL');
CREATE TYPE seniority_level AS ENUM ('JUNIOR', 'MID', 'SENIOR', 'LEAD');
CREATE TYPE profile_source AS ENUM ('AI', 'AI_FALLBACK', 'HR');

CREATE TABLE job_role_profiles (
    job_id UUID PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
    role_family role_family NOT NULL,
    is_technical BOOLEAN NOT NULL,
    languages JSONB,  -- ["Python", "Java", "Go"]
    frameworks JSONB, -- ["Spring", "Django", "FastAPI"]
    seniority seniority_level NOT NULL,
    skill_weights JSONB NOT NULL, -- {"python": 5, "spring": 4, "rest_api": 3, ...}
    source profile_source NOT NULL DEFAULT 'AI',
    edited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    extracted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT skill_weights_values CHECK (
        COALESCE((skill_weights->'python')::INT, 0) >= 1 AND
        COALESCE((skill_weights->'python')::INT, 0) <= 5
    )
);

CREATE INDEX idx_job_role_profiles_source ON job_role_profiles(source);
CREATE INDEX idx_job_role_profiles_role_family ON job_role_profiles(role_family);
