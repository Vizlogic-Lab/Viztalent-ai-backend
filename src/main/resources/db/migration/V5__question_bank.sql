-- Phase 5: question bank — recruiter-uploaded questions the OA generator
-- (Phase 6) can blend in alongside AI-written ones. Shared across all jobs
-- (no job_id) per SMARTSTAFF_BACKEND_DESIGN.md §5's "job_id nullable = shared".
CREATE TABLE question_bank_uploads (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename    VARCHAR(255) NOT NULL,
    uploaded_by VARCHAR(255),
    item_count  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE question_bank_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_id        UUID NOT NULL REFERENCES question_bank_uploads (id) ON DELETE CASCADE,
    type             VARCHAR(16) NOT NULL CHECK (type IN ('MCQ', 'MSQ', 'DESCRIPTIVE', 'CODING')),
    level            VARCHAR(8),
    skill            VARCHAR(255),
    difficulty       VARCHAR(16),
    prompt           TEXT NOT NULL,
    options          JSONB NOT NULL DEFAULT '[]',
    correct_indices  JSONB NOT NULL DEFAULT '[]',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_question_bank_items_upload_id ON question_bank_items (upload_id);
