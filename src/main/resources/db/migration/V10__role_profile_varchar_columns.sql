-- V9 declared role_family/seniority/source as PostgreSQL ENUM types, which
-- JPA's string-bound enums can't insert into, and a CHECK that required every
-- profile to carry a "python" weight. Switch to VARCHAR + CHECK like the rest
-- of the schema and replace the python check with a shape check.
ALTER TABLE job_role_profiles DROP CONSTRAINT skill_weights_values;

ALTER TABLE job_role_profiles ALTER COLUMN source DROP DEFAULT;
ALTER TABLE job_role_profiles
    ALTER COLUMN role_family TYPE VARCHAR(16) USING role_family::text,
    ALTER COLUMN seniority   TYPE VARCHAR(8)  USING seniority::text,
    ALTER COLUMN source      TYPE VARCHAR(16) USING source::text;
ALTER TABLE job_role_profiles ALTER COLUMN source SET DEFAULT 'AI';

ALTER TABLE job_role_profiles
    ADD CONSTRAINT ck_job_role_profiles_role_family
        CHECK (role_family IN ('BACKEND', 'FRONTEND', 'FULLSTACK', 'DATA', 'DEVOPS', 'QA', 'MOBILE', 'NON_TECHNICAL')),
    ADD CONSTRAINT ck_job_role_profiles_seniority
        CHECK (seniority IN ('JUNIOR', 'MID', 'SENIOR', 'LEAD')),
    ADD CONSTRAINT ck_job_role_profiles_source
        CHECK (source IN ('AI', 'AI_FALLBACK', 'HR')),
    ADD CONSTRAINT ck_job_role_profiles_skill_weights
        CHECK (jsonb_typeof(skill_weights) = 'object');

DROP TYPE role_family;
DROP TYPE seniority_level;
DROP TYPE profile_source;
