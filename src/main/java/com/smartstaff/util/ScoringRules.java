package com.smartstaff.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Pure scoring math (unit-tested), separate from the sandbox and Gemini calls
 *  the scoring job makes around it. All the "fraction" methods return a share
 *  in [0, 1] of a question's points. */
public final class ScoringRules {

    /** A CODE_WRITE question's points split: 70% from tests passing, 30% from
     *  the AI rubric (approach/complexity/edge-cases/readability). */
    public static final double CODE_WRITE_TEST_SHARE = 0.70;
    public static final double CODE_WRITE_RUBRIC_SHARE = 0.30;

    private ScoringRules() {}

    /** MCQ: 1 when the single selected option is the correct one, else 0. */
    public static double mcq(List<Integer> correct, List<Integer> selected) {
        return correct.size() == 1 && selected.size() == 1 && selected.get(0).equals(correct.get(0)) ? 1.0 : 0.0;
    }

    /** MSQ partial credit: (correctly chosen − wrongly chosen) / number correct,
     *  clamped to [0, 1]. Full marks need exactly the correct set; ticking
     *  everything is penalised. */
    public static double msq(List<Integer> correct, List<Integer> selected) {
        if (correct.isEmpty()) return 0.0;
        Set<Integer> correctSet = Set.copyOf(correct);
        long right = selected.stream().distinct().filter(correctSet::contains).count();
        long wrong = selected.stream().distinct().filter(i -> !correctSet.contains(i)).count();
        double raw = (right - wrong) / (double) correctSet.size();
        return clamp01(raw);
    }

    /** LOGIC / CODE_OUTPUT free text: case-insensitive after OutputComparator's
     *  whitespace normalisation. LOGIC answers are single short values; a
     *  CODE_OUTPUT answer is compared to the program's real output. */
    public static boolean textMatches(String expected, String actual) {
        return OutputComparator.normalise(expected == null ? "" : expected).strip().equalsIgnoreCase(
                OutputComparator.normalise(actual == null ? "" : actual).strip());
    }

    /** Weighted proportion of coding tests passed. */
    public static double testRatio(double passedWeight, double totalWeight) {
        return totalWeight <= 0 ? 0.0 : clamp01(passedWeight / totalWeight);
    }

    /** Combine the test proportion and rubric proportion into a CODE_WRITE
     *  fraction. When the rubric couldn't be graded, pass rubricGraded=false and
     *  only the 70% test share counts (the caller flags the rest for review). */
    public static double codeWrite(double testRatio, double rubricRatio, boolean rubricGraded) {
        double tests = CODE_WRITE_TEST_SHARE * clamp01(testRatio);
        double rubric = rubricGraded ? CODE_WRITE_RUBRIC_SHARE * clamp01(rubricRatio) : 0.0;
        return tests + rubric;
    }

    public static BigDecimal points(double fraction, int maxPoints) {
        return BigDecimal.valueOf(clamp01(fraction) * maxPoints).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal total, BigDecimal max) {
        if (max == null || max.signum() <= 0) return BigDecimal.ZERO;
        return total.multiply(BigDecimal.valueOf(100)).divide(max, 2, RoundingMode.HALF_UP);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
