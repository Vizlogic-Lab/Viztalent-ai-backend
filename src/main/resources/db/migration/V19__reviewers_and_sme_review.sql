-- F11: technical reviewer (SME) accounts and per-job assignment.
-- F12: SME review of question sets and of results, with a full audit trail.

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'USER', 'REVIEWER'));
ALTER TABLE users ADD COLUMN reviewer_skills JSONB NOT NULL DEFAULT '[]';

-- Reviewers sign in by email, like admins.
CREATE UNIQUE INDEX ux_users_reviewer_email
    ON users (lower(email))
    WHERE role = 'REVIEWER';

CREATE TABLE job_reviewers (
    job_id      UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_by VARCHAR(255),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, user_id)
);
CREATE INDEX ix_job_reviewers_user ON job_reviewers (user_id);

-- {question_set_approval_required, sample_rate_not_recommended, review_if_theory_at_least}; NULL = defaults.
ALTER TABLE jobs ADD COLUMN review_policy JSONB;

CREATE TABLE sme_reviews (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type         VARCHAR(16) NOT NULL CHECK (subject_type IN ('QUESTION_SET', 'ATTEMPT')),
    subject_id           UUID NOT NULL,
    job_id               UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    reviewer_id          UUID REFERENCES users(id) ON DELETE SET NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'ADJUSTED', 'RETURNED')),
    adjustments          JSONB NOT NULL DEFAULT '[]',
    final_theory_band    VARCHAR(20),
    final_hands_on_band  VARCHAR(20),
    final_recommendation VARCHAR(24),
    comment              TEXT,
    due_at               TIMESTAMPTZ,
    signed_off_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One review per subject.
CREATE UNIQUE INDEX ux_sme_reviews_subject ON sme_reviews (subject_type, subject_id);
CREATE INDEX ix_sme_reviews_job ON sme_reviews (job_id);

CREATE TABLE review_audit (
    id        BIGSERIAL PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES sme_reviews(id) ON DELETE CASCADE,
    actor_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    action    VARCHAR(32) NOT NULL,
    before    JSONB,
    after     JSONB,
    at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_review_audit_review ON review_audit (review_id, at);

-- F12.6: the SME's score next to the automatic one, per question (for F15 calibration).
ALTER TABLE attempt_answers
    ADD COLUMN sme_score  NUMERIC(6, 2),
    ADD COLUMN sme_reason TEXT;

-- Why an attempt was validated without a reviewer ("sampling").
ALTER TABLE assessment_attempts ADD COLUMN validation_reason VARCHAR(32);
