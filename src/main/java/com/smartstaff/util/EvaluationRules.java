package com.smartstaff.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/** Pure evaluation rules (F9), unit-tested. The evaluation job wraps these
 *  around the sandbox runs and the Gemini rubric grader. "Fraction" methods
 *  return a share in [0, 1] of a question's points. */
public final class EvaluationRules {

    private EvaluationRules() {}

    // ── objective layer ─────────────────────────────────────────────────

    /** MCQ: exact — the single pick is the correct option. */
    public static double mcq(List<Integer> correct, List<Integer> selected) {
        return correct.size() == 1 && selected.size() == 1 && selected.get(0).equals(correct.get(0)) ? 1.0 : 0.0;
    }

    /** MSQ: +1 per correct pick, −1 per wrong pick, floor 0, scaled by the number of correct options. */
    public static double msq(List<Integer> correct, List<Integer> selected) {
        if (correct.isEmpty()) return 0.0;
        Set<Integer> c = new HashSet<>(correct);
        long right = selected.stream().distinct().filter(c::contains).count();
        long wrong = selected.stream().distinct().filter(i -> !c.contains(i)).count();
        return clamp01((right - wrong) / (double) c.size());
    }

    /** LOGIC: exact after trimming and case-folding; numbers equal within 1e-6. */
    public static boolean logicMatches(String expected, String actual) {
        String e = expected == null ? "" : expected.trim();
        String a = actual == null ? "" : actual.trim();
        if (e.equalsIgnoreCase(a)) return true;
        Double x = number(e), y = number(a);
        return x != null && y != null && Math.abs(x - y) <= 1e-6;
    }

    /** CODE_OUTPUT: exact after normalising line endings and trailing whitespace (case matters). */
    public static boolean codeOutputMatches(String expected, String actual) {
        return OutputComparator.normalise(expected).equals(OutputComparator.normalise(actual));
    }

    /** One test's outcome when the final code was run against all tests. */
    public record TestOutcome(boolean visible, String category, BigDecimal weight, boolean passed, String status) {}

    /** Correctness of a coding answer: weighted pass share of the HIDDEN tests
     *  only (weights come from the tests: EDGE/LARGE 1.5, BASIC 1.0). */
    public static double hiddenShare(List<TestOutcome> tests) {
        double total = 0, passed = 0;
        for (TestOutcome t : tests) {
            if (t.visible()) continue;
            double w = t.weight() == null ? 1.0 : t.weight().doubleValue();
            total += w;
            if (t.passed()) passed += w;
        }
        return total <= 0 ? 0.0 : clamp01(passed / total);
    }

    public static int hiddenPassed(List<TestOutcome> tests) {
        return (int) tests.stream().filter(t -> !t.visible() && t.passed()).count();
    }

    public static int hiddenTotal(List<TestOutcome> tests) {
        return (int) tests.stream().filter(t -> !t.visible()).count();
    }

    // ── stringency ──────────────────────────────────────────────────────

    /** Rubric credit is halved below `halfBelow` hidden pass share and removed below `zeroBelow`. */
    public static double rubricMultiplier(double hiddenShare, double halfBelow, double zeroBelow) {
        if (hiddenShare < zeroBelow) return 0.0;
        if (hiddenShare < halfBelow) return 0.5;
        return 1.0;
    }

    private static final Map<String, Pattern> READS_INPUT = Map.of(
            "java", Pattern.compile("Scanner|System\\.in|BufferedReader|InputStreamReader|readLine|DataInputStream"),
            "python", Pattern.compile("\\binput\\s*\\(|sys\\.stdin|fileinput|open\\(\\s*0"),
            "javascript", Pattern.compile("process\\.stdin|readline|readFileSync\\(\\s*(0|['\"]/dev/stdin)|require\\(['\"]fs"),
            "cpp", Pattern.compile("\\bcin\\b|scanf|getline|getchar|fread|std::cin"));

    /** Does the program read standard input at all? (Unknown language: assume yes.) */
    public static boolean readsInput(String language, String code) {
        Pattern p = READS_INPUT.get(language == null ? "" : language);
        return p == null || p.matcher(stripComments(language, code)).find();
    }

    /** How many of the visible tests' distinctive expected outputs (≥ minLength
     *  characters) appear verbatim in the code as literals. */
    public static int literalOutputMatches(String language, String code, List<String> visibleExpected, int minLength) {
        String body = stripComments(language, code);
        Set<String> seen = new HashSet<>();
        int matches = 0;
        for (String raw : visibleExpected) {
            String e = raw == null ? "" : raw.trim();
            if (e.length() < minLength || !seen.add(e)) continue;
            Pattern literal = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(e) + "(?![A-Za-z0-9_])");
            if (literal.matcher(body).find()) matches++;
        }
        return matches;
    }

    /** Hard-coding: the answer ignores its input although the tests provide
     *  some, or it embeds two or more of the visible expected outputs. */
    public static boolean isHardcoded(String language, String code, List<String> visibleExpected,
                                      boolean testsHaveInput, int minLiteralLength) {
        if (code == null || code.isBlank()) return false;
        if (testsHaveInput && !readsInput(language, code)) return true;
        return literalOutputMatches(language, code, visibleExpected, minLiteralLength) >= 2;
    }

    /** Real code, not just the starter or comments/plans: the code minus comments
     *  and whitespace is non-empty and differs from the starter's. */
    public static boolean hasRealCode(String language, String code, String starter) {
        String c = squash(stripComments(language, code));
        if (c.isEmpty()) return false;
        return !c.equals(squash(stripComments(language, starter)));
    }

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern HASH_COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern PY_DOCSTRING = Pattern.compile("(\"\"\"|''').*?\\1", Pattern.DOTALL);

    /** Removes comments (and Python docstrings). Good enough for scoring heuristics. */
    public static String stripComments(String language, String code) {
        if (code == null) return "";
        if ("python".equals(language)) {
            return HASH_COMMENT.matcher(PY_DOCSTRING.matcher(code).replaceAll("")).replaceAll("");
        }
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll("")).replaceAll("");
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    // ── rubric consistency & evidence ───────────────────────────────────

    /** Two rubric runs disagree when their totals differ by more than `threshold` of the question's points. */
    public static boolean disagrees(double total1, double total2, double points, double threshold) {
        return Math.abs(total1 - total2) > threshold * points;
    }

    public static double median(double a, double b, double c) {
        double[] v = {a, b, c};
        Arrays.sort(v);
        return v[1];
    }

    /** A rubric score only counts when its evidence is quoted from the
     *  candidate's answer (whitespace-insensitive, at least 3 characters). */
    public static boolean evidenceFound(String evidence, String answer) {
        String e = squash(evidence);
        if (e.length() < 3) return false;
        return squash(answer).contains(e);
    }

    public static BigDecimal points(double fraction, int maxPoints) {
        return BigDecimal.valueOf(clamp01(fraction) * maxPoints).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal earned, BigDecimal possible) {
        if (possible == null || possible.signum() <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return earned.multiply(BigDecimal.valueOf(100)).divide(possible, 2, RoundingMode.HALF_UP);
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
