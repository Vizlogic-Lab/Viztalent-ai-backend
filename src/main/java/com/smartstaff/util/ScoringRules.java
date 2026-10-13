package com.smartstaff.util;

import com.smartstaff.entity.Competency;
import com.smartstaff.entity.Dimension;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.entity.ScoringSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** F10 — turns graded questions into Theory vs Hands-on results. Pure.
 *
 *  theory / hands_on = points earned ÷ points possible over that dimension's
 *  questions × 100; overall = weighted (0.3 / 0.7 by default, per job). When a
 *  level has no questions of one dimension, that score is null and overall is
 *  the other one. Gates, in order: any grading-uncertainty or integrity concern
 *  (GRADER_DISAGREEMENT, HARDCODED, *_UNAVAILABLE, more than max_tab_switches)
 *  → HR_REVIEW; hands_on ≥ 60 and theory ≥ 50 → PROCEED; hands_on 50–59 →
 *  HR_REVIEW; else NOT_RECOMMENDED. theory − hands_on ≥ 25 adds
 *  THEORY_PRACTICE_GAP (informational, not a gate). */
public final class ScoringRules {

    private ScoringRules() {}

    public static final List<String> REVIEW_FLAGS =
            List.of("GRADER_DISAGREEMENT", "HARDCODED", "RUBRIC_UNAVAILABLE", "RUNNER_UNAVAILABLE");
    public static final String GAP_FLAG = "THEORY_PRACTICE_GAP";
    public static final String TAB_FLAG = "EXCESSIVE_TAB_SWITCHES";

    public record Scored(QuestionType type, Dimension dimension, Competency competency, double earned, int points) {}

    public record Result(BigDecimal theory, BigDecimal handsOn, BigDecimal overall, String theoryBand,
                         String handsOnBand, Map<String, BigDecimal> competencies, String recommendation,
                         List<String> flags) {}

    /** Non-technical role profiles: hands-on is scenario/logic only; everything else counts as theory. */
    public static Dimension dimensionFor(QuestionType type, Dimension stored, boolean technical) {
        if (!technical) return type == QuestionType.SCENARIO || type == QuestionType.LOGIC ? Dimension.HANDS_ON : Dimension.THEORY;
        return stored != null ? stored : type.dimension();
    }

    public static String band(BigDecimal score) {
        if (score == null) return null;
        double s = score.doubleValue();
        return s >= 80 ? "STRONG" : s >= 60 ? "WORKING" : s >= 40 ? "BASIC" : "NOT_DEMONSTRATED";
    }

    public static Result score(List<Scored> questions, ScoringSettings settings, Collection<String> questionFlags,
                               long tabSwitches) {
        return score(questions, settings, questionFlags, tabSwitches, false);
    }

    /** proctoringConcern (ProctoringRules.concern) adds PROCTORING_CONCERN and forces HR_REVIEW. */
    public static Result score(List<Scored> questions, ScoringSettings settings, Collection<String> questionFlags,
                               long tabSwitches, boolean proctoringConcern) {
        ScoringSettings s = ScoringSettings.orDefaults(settings);
        BigDecimal theory = percent(questions, q -> q.dimension() == Dimension.THEORY);
        BigDecimal handsOn = percent(questions, q -> q.dimension() == Dimension.HANDS_ON);

        BigDecimal overall;
        if (theory == null && handsOn == null) overall = null;
        else if (theory == null) overall = handsOn;
        else if (handsOn == null) overall = theory;
        else overall = BigDecimal.valueOf(s.theory_weight() * theory.doubleValue() + s.hands_on_weight() * handsOn.doubleValue())
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, BigDecimal> competencies = new LinkedHashMap<>();
        for (Competency c : Competency.values()) {
            BigDecimal p = percent(questions, q -> q.competency() == c);
            if (p != null) competencies.put(c.name(), p);
        }

        List<String> flags = new ArrayList<>();
        double t = theory == null ? 0 : theory.doubleValue();
        double h = handsOn == null ? 0 : handsOn.doubleValue();
        if (theory != null && handsOn != null && t - h >= s.theory_practice_gap()) flags.add(GAP_FLAG);
        boolean tabs = tabSwitches > s.max_tab_switches();
        if (tabs) flags.add(TAB_FLAG);
        if (proctoringConcern) flags.add(ProctoringRules.CONCERN_FLAG);
        boolean concern = tabs || proctoringConcern || questionFlags.stream().anyMatch(REVIEW_FLAGS::contains);

        // A missing dimension doesn't block its gate.
        boolean handsOnPass = handsOn == null || h >= s.proceed_hands_on_min();
        boolean theoryPass = theory == null || t >= s.proceed_theory_min();
        String recommendation;
        if (overall == null || concern) recommendation = "HR_REVIEW";
        else if (handsOnPass && theoryPass) recommendation = "PROCEED";
        else if (handsOn != null && h >= s.review_hands_on_min() && h < s.proceed_hands_on_min()) recommendation = "HR_REVIEW";
        else recommendation = "NOT_RECOMMENDED";

        return new Result(theory, handsOn, overall, band(theory), band(handsOn), competencies, recommendation, flags);
    }

    private static BigDecimal percent(List<Scored> questions, java.util.function.Predicate<Scored> which) {
        double earned = 0, possible = 0;
        for (Scored q : questions) {
            if (!which.test(q)) continue;
            earned += q.earned();
            possible += q.points();
        }
        if (possible <= 0) return null;
        return BigDecimal.valueOf(Math.min(100.0, earned / possible * 100)).setScale(2, RoundingMode.HALF_UP);
    }
}
