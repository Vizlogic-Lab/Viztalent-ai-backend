-- Company-wide question book: every saved question (hand-written or AI-written), copied into
-- papers when an employer picks it. The content is the same request body the paper editor sends,
-- so a copy goes through the same validation and marking as a hand-written question.
CREATE TABLE question_book_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type         VARCHAR(16)  NOT NULL CHECK (type IN ('MCQ', 'ONE_WORD', 'SCENARIO', 'PROJECT', 'DEBUG')),
    skill        VARCHAR(120),
    difficulty   VARCHAR(8)   CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    marks        INTEGER      NOT NULL DEFAULT 1,
    prompt       TEXT         NOT NULL,
    prompt_hash  VARCHAR(64)  NOT NULL UNIQUE,
    content      JSONB        NOT NULL,
    origin       VARCHAR(8)   NOT NULL CHECK (origin IN ('AI', 'MANUAL')),
    created_by   VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usage_count  INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX ix_question_book_type_difficulty ON question_book_items (type, difficulty);
CREATE INDEX ix_question_book_skill ON question_book_items (lower(skill));
