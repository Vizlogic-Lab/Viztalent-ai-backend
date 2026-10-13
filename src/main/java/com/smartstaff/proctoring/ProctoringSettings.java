package com.smartstaff.proctoring;

/** Admin-chosen proctoring checks and how long a detection must persist (seconds of
 *  consecutive frames) before it becomes an event. Snapshots are not built yet:
 *  no image of the candidate is ever stored or uploaded. */
public record ProctoringSettings(
        boolean eye_tracking,
        boolean phone_detection,
        int no_face_seconds,
        int multiple_faces_seconds,
        int looking_away_seconds,
        int phone_seconds,
        int mic_silent_seconds
) {
    public static final ProctoringSettings DEFAULTS = new ProctoringSettings(true, false, 3, 2, 3, 2, 60);

    /** Missing values (older saved JSON) take the defaults. */
    public ProctoringSettings withDefaults() {
        return new ProctoringSettings(eye_tracking, phone_detection,
                orDefault(no_face_seconds, DEFAULTS.no_face_seconds), orDefault(multiple_faces_seconds, DEFAULTS.multiple_faces_seconds),
                orDefault(looking_away_seconds, DEFAULTS.looking_away_seconds), orDefault(phone_seconds, DEFAULTS.phone_seconds),
                orDefault(mic_silent_seconds, DEFAULTS.mic_silent_seconds));
    }

    /** Null when valid, else what's wrong. */
    public String validate() {
        if (outOf(no_face_seconds, 1, 60) || outOf(multiple_faces_seconds, 1, 60) || outOf(looking_away_seconds, 1, 60)
                || outOf(phone_seconds, 1, 60)) {
            return "Detection hold times must be between 1 and 60 seconds.";
        }
        if (outOf(mic_silent_seconds, 10, 600)) return "The silent-microphone window must be between 10 and 600 seconds.";
        return null;
    }

    private static int orDefault(int v, int d) { return v <= 0 ? d : v; }
    private static boolean outOf(int v, int min, int max) { return v < min || v > max; }
}
