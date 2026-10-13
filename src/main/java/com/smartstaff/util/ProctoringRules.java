package com.smartstaff.util;

import com.smartstaff.entity.AttemptEventType;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Server-side proctoring rules: the warning count (from stored events, so a
 *  client can't reset it) and the F10 integrity gate. Proctoring never ends a
 *  test; a concern sends the result to HR review. */
public final class ProctoringRules {

    private ProctoringRules() {}

    public static final String CONCERN_FLAG = "PROCTORING_CONCERN";

    /** Event types that are warnings. */
    public static final Set<AttemptEventType> WARNING_TYPES = EnumSet.of(
            AttemptEventType.TAB_SWITCH, AttemptEventType.FULLSCREEN_EXIT, AttemptEventType.CAMERA_OFF,
            AttemptEventType.MIC_OFF, AttemptEventType.MIC_MUTED, AttemptEventType.MIC_LOST);

    /** Types the client may never send (legacy / server-owned). */
    public static final Set<AttemptEventType> SERVER_ONLY = EnumSet.of(AttemptEventType.WARNING, AttemptEventType.TERMINATED);

    /** One physical action (Alt+Tab fires blur, visibility and fullscreen events) is one warning. */
    public static final Duration COALESCE = Duration.ofSeconds(4);

    // F10 gate: any of these sends the result to HR review.
    public static final int CONCERN_WARNINGS = 3;
    public static final long CONCERN_AWAY_SECONDS = 60;

    public record Stamped(AttemptEventType type, Instant at) {}

    /** Warning-type events, with events within COALESCE of the last counted one merged. */
    public static int countWarnings(List<Stamped> events) {
        int n = 0;
        Instant last = null;
        for (Stamped e : events.stream().filter(x -> WARNING_TYPES.contains(x.type()))
                .sorted(Comparator.comparing(Stamped::at)).toList()) {
            if (last == null || Duration.between(last, e.at()).compareTo(COALESCE) >= 0) {
                n++;
                last = e.at();
            }
        }
        return n;
    }

    /** True when the attempt should go to HR review on integrity grounds. */
    public static boolean concern(int warnings, long multipleFaces, long phones, long noFaceAndAwaySeconds) {
        return warnings >= CONCERN_WARNINGS || multipleFaces > 0 || phones > 0
                || noFaceAndAwaySeconds > CONCERN_AWAY_SECONDS;
    }
}
