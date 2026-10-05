-- Personal assessments: one generated per candidate from the JD + their resume,
-- using the admin-editable prompt (setting assessment_prompt). They are never the
-- job's "current" version, so the job-level unique index is untouched; the
-- candidate's latest READY one is looked up by candidate_id instead.
ALTER TABLE assessments ADD COLUMN candidate_id UUID REFERENCES candidates (id) ON DELETE CASCADE;
CREATE INDEX ix_assessments_candidate ON assessments (candidate_id) WHERE candidate_id IS NOT NULL;

-- Webcam / mic proctoring signals. Strikes (TAB_SWITCH, FULLSCREEN_EXIT,
-- CAMERA_OFF, MIC_OFF) count toward the 3-warning limit; NO_FACE,
-- MULTIPLE_FACES and LOOKING_AWAY are logged for the reviewer only.
ALTER TABLE attempt_events DROP CONSTRAINT attempt_events_type_check;
ALTER TABLE attempt_events ADD CONSTRAINT attempt_events_type_check CHECK (type IN (
    'TAB_SWITCH', 'PASTE', 'FOCUS_LOST', 'FULLSCREEN_EXIT',
    'CAMERA_OFF', 'MIC_OFF', 'NO_FACE', 'MULTIPLE_FACES', 'LOOKING_AWAY',
    'WARNING', 'TERMINATED', 'PROCTORING_STARTED'));
