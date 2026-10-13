-- Phone-in-view flag from the candidate page's object detector (review only).
ALTER TABLE attempt_events DROP CONSTRAINT attempt_events_type_check;
ALTER TABLE attempt_events ADD CONSTRAINT attempt_events_type_check CHECK (type IN (
    'TAB_SWITCH', 'PASTE', 'FOCUS_LOST', 'FULLSCREEN_EXIT',
    'CAMERA_OFF', 'MIC_OFF', 'NO_FACE', 'MULTIPLE_FACES', 'LOOKING_AWAY', 'PHONE_DETECTED',
    'WARNING', 'TERMINATED', 'PROCTORING_STARTED'));
