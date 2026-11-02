package com.smartstaff.util;

import com.smartstaff.entity.LevelPassMarks;
import com.smartstaff.entity.ScoringSettings;

import java.util.Map;

/** Pass marks per assessment level (see {@link LevelPassMarks}). Pure.
 *
 *  For an attempt at a level, each mark comes from the first place that sets it:
 *  the job's override for that level, then the global value for that level, then the
 *  job's scoring settings (or the built-in defaults). Weights and the review band are
 *  always the job's own. */
public final class PassMarkRules {

    private PassMarkRules() {}

    /** Null when valid, else what's wrong. Levels must be L1, L2 or L3. */
    public static String problem(Map<String, LevelPassMarks> marks) {
        if (marks == null) return null;
        for (Map.Entry<String, LevelPassMarks> e : marks.entrySet()) {
            if (!java.util.List.of("L1", "L2", "L3").contains(e.getKey())) return "Unknown level " + e.getKey() + ".";
            if (e.getValue() == null) continue;
            String p = e.getValue().problem();
            if (p != null) return e.getKey() + ": " + p;
        }
        return null;
    }

    /** The scoring settings to grade one attempt with. level may be null (interviews): then no level marks apply. */
    public static ScoringSettings effective(ScoringSettings base, Map<String, LevelPassMarks> global,
                                            Map<String, LevelPassMarks> jobOverride, String level) {
        ScoringSettings s = ScoringSettings.orDefaults(base);
        if (level == null) return s;
        LevelPassMarks job = jobOverride == null ? null : jobOverride.get(level);
        LevelPassMarks glob = global == null ? null : global.get(level);
        return new ScoringSettings(s.theory_weight(), s.hands_on_weight(),
                pick(job == null ? null : job.proceed_hands_on_min(), glob == null ? null : glob.proceed_hands_on_min(), s.proceed_hands_on_min()),
                pick(job == null ? null : job.proceed_theory_min(), glob == null ? null : glob.proceed_theory_min(), s.proceed_theory_min()),
                s.review_hands_on_min(), s.max_tab_switches(), s.theory_practice_gap(),
                pick(job == null ? null : job.overall_pass(), glob == null ? null : glob.overall_pass(), s.overall_pass()));
    }

    private static int pick(Integer job, Integer global, int fallback) {
        if (job != null) return job;
        if (global != null) return global;
        return fallback;
    }
}
