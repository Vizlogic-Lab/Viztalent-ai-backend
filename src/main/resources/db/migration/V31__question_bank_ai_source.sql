-- AI-generated questions are harvested into the question bank after validation.
-- source tells them apart from HR uploads; content_hash stops the same question being stored twice.
ALTER TABLE question_bank_items
    ADD COLUMN source       VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
    ADD COLUMN content_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_question_bank_items_content_hash
    ON question_bank_items (content_hash) WHERE content_hash IS NOT NULL;
