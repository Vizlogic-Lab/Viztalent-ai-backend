-- Microphone states replace the single MIC_OFF (kept for old rows):
-- MIC_MUTED (track muted/disabled/stopped) and MIC_LOST (device gone) are
-- warnings; MIC_SILENT (long silence on a spoken question) is a review flag.
ALTER TABLE attempt_events DROP CONSTRAINT attempt_events_type_check;
ALTER TABLE attempt_events ADD CONSTRAINT attempt_events_type_check CHECK (type IN (
    'TAB_SWITCH', 'PASTE', 'FOCUS_LOST', 'FULLSCREEN_EXIT',
    'CAMERA_OFF', 'MIC_OFF', 'MIC_MUTED', 'MIC_LOST', 'MIC_SILENT',
    'NO_FACE', 'MULTIPLE_FACES', 'LOOKING_AWAY', 'PHONE_DETECTED',
    'WARNING', 'TERMINATED', 'PROCTORING_STARTED'));
