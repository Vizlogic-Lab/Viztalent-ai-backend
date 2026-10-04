-- F17: invite / reminder / rejection emails sent from the backend.
-- The rendered email (which holds the invite link) is never stored: invites keep
-- only token hashes, so a retry re-issues the token instead.
CREATE TABLE email_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id         UUID REFERENCES jobs (id) ON DELETE CASCADE,
    invite_id      UUID REFERENCES invites (id) ON DELETE SET NULL,
    to_address     VARCHAR(320) NOT NULL,
    candidate_name VARCHAR(255),
    template       VARCHAR(32) NOT NULL
                   CHECK (template IN ('ASSESSMENT_INVITE', 'INTERVIEW_INVITE', 'REMINDER', 'REJECTION')),
    status         VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    error          TEXT,
    attempts       INT NOT NULL DEFAULT 0,
    created_by     VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);
CREATE INDEX ix_email_log_job ON email_log (job_id, created_at DESC);
CREATE INDEX ix_email_log_invite ON email_log (invite_id);

-- Set when the 24-hours-before-expiry reminder has gone out.
ALTER TABLE invites ADD COLUMN reminder_sent_at TIMESTAMPTZ;
