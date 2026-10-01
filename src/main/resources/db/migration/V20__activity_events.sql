-- F14: activity feed written by the services (assessment generated/approved,
-- invite sent, attempt submitted, evaluated, validated). Append-only.
CREATE TABLE activity_events (
    id         BIGSERIAL PRIMARY KEY,
    job_id     UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    kind       VARCHAR(32) NOT NULL,
    message    TEXT NOT NULL,
    subject_id UUID,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_activity_events_job_at ON activity_events (job_id, at DESC);
