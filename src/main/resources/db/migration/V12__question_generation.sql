-- Practical question generation (F4): the extra fields each question type
-- carries, generation progress, and which assessment version an invite uses.

ALTER TABLE assessment_questions
    ADD COLUMN title               TEXT,
    ADD COLUMN constraints         TEXT,
    ADD COLUMN input_format        TEXT,
    ADD COLUMN output_format       TEXT,
    ADD COLUMN explanation         TEXT,
    ADD COLUMN expected_complexity VARCHAR(64),
    ADD COLUMN bug_descriptions    JSONB       NOT NULL DEFAULT '[]',
    ADD COLUMN origin              VARCHAR(8)  NOT NULL DEFAULT 'AI' CHECK (origin IN ('AI', 'BANK')),
    ADD COLUMN bank_item_id        UUID REFERENCES question_bank_items (id) ON DELETE SET NULL;

-- Questions in legacy CUSTOM assessments came from the bank (AI only padded shortfalls).
UPDATE assessment_questions q SET origin = 'BANK'
FROM assessments a
WHERE q.assessment_id = a.id AND a.source = 'CUSTOM';

-- GenerationProgress as JSON: {slotsTotal, slotsDone, filled: {L1: n}, unfilled: [...], errors: [...]}
ALTER TABLE assessments ADD COLUMN progress JSONB NOT NULL DEFAULT '{}';

ALTER TABLE invites ADD COLUMN assessment_id UUID REFERENCES assessments (id) ON DELETE SET NULL;
CREATE INDEX ix_invites_assessment_id ON invites (assessment_id);
