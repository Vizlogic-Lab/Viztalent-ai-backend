package com.smartstaff.entity;

/** Integrity signals the candidate page reports. Strikes (TAB_SWITCH,
 *  FULLSCREEN_EXIT, CAMERA_OFF, MIC_OFF) count toward the 3-warning limit;
 *  NO_FACE, MULTIPLE_FACES and LOOKING_AWAY are logged for the reviewer only. */
public enum AttemptEventType {
    TAB_SWITCH, PASTE, FOCUS_LOST, FULLSCREEN_EXIT,
    CAMERA_OFF, MIC_OFF, NO_FACE, MULTIPLE_FACES, LOOKING_AWAY,
    WARNING, TERMINATED, PROCTORING_STARTED
}
