-- Hand-authored 50-question assessments (25 MCQ / 15 ONE_WORD / 5 SCENARIO / 5 PROJECT).
-- lifecycle is separate from status: status tracks AI generation (QUEUED..READY),
-- lifecycle tracks authoring (DRAFT -> PUBLISHED -> ARCHIVED). Existing versions
-- were usable as soon as they were READY, so they count as PUBLISHED.
ALTER TABLE assessments
    ADD COLUMN lifecycle      VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN title          TEXT,
    ADD COLUMN marking_scheme JSONB,
    ADD COLUMN updated_at     TIMESTAMPTZ;

ALTER TABLE assessments
    ADD CONSTRAINT ck_assessments_lifecycle CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'));

-- ONE_WORD: every answer that earns full marks besides model_answer (synonyms, spellings).
ALTER TABLE assessment_questions
    ADD COLUMN accepted_answers JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE assessment_questions DROP CONSTRAINT ck_assessment_questions_type;
ALTER TABLE assessment_questions ADD CONSTRAINT ck_assessment_questions_type CHECK (type IN
    ('MCQ', 'MSQ', 'CODE_WRITE', 'CODE_DEBUG', 'CODE_OUTPUT', 'SCENARIO', 'LOGIC', 'ONE_WORD', 'PROJECT'));

-- MANUAL: typed in by the assessment's author.
ALTER TABLE assessment_questions DROP CONSTRAINT assessment_questions_origin_check;
ALTER TABLE assessment_questions ADD CONSTRAINT assessment_questions_origin_check CHECK (origin IN ('AI', 'BANK', 'MANUAL'));

ALTER TABLE question_bank_items DROP CONSTRAINT ck_question_bank_items_type;
ALTER TABLE question_bank_items ADD CONSTRAINT ck_question_bank_items_type CHECK (type IN
    ('MCQ', 'MSQ', 'CODE_WRITE', 'CODE_DEBUG', 'CODE_OUTPUT', 'SCENARIO', 'LOGIC', 'ONE_WORD', 'PROJECT'));
