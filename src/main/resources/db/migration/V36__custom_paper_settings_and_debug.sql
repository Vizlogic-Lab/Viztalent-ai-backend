-- Custom papers: DEBUG question type. Section counts, total questions, duration and
-- passing mark live in assessments.marking_scheme (JSONB), so no new columns are needed.
ALTER TABLE assessment_questions DROP CONSTRAINT ck_assessment_questions_type;
ALTER TABLE assessment_questions ADD CONSTRAINT ck_assessment_questions_type CHECK (type IN
    ('MCQ', 'MSQ', 'CODE_WRITE', 'CODE_DEBUG', 'CODE_OUTPUT', 'SCENARIO', 'LOGIC', 'ONE_WORD', 'PROJECT', 'DEBUG'));
