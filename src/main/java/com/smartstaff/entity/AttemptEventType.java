package com.smartstaff.entity;

/** Integrity signals the candidate page reports.
 *  Warnings (counted by the server, see ProctoringRules): TAB_SWITCH, FULLSCREEN_EXIT,
 *  CAMERA_OFF, MIC_MUTED, MIC_LOST (and the legacy MIC_OFF).
 *  Review flags: NO_FACE, MULTIPLE_FACES, LOOKING_AWAY, PHONE_DETECTED, MIC_SILENT.
 *  WARNING and TERMINATED are legacy rows only: the server no longer accepts them
 *  from the client, and proctoring never ends a test. */
public enum AttemptEventType {
    TAB_SWITCH, PASTE, FOCUS_LOST, FULLSCREEN_EXIT,
    CAMERA_OFF, MIC_OFF, MIC_MUTED, MIC_LOST, MIC_SILENT,
    NO_FACE, MULTIPLE_FACES, LOOKING_AWAY, PHONE_DETECTED,
    WARNING, TERMINATED, PROCTORING_STARTED
}
