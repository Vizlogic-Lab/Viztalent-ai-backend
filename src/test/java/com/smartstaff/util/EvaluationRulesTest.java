package com.smartstaff.util;

import com.smartstaff.util.EvaluationRules.TestOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationRulesTest {

    private static final BigDecimal ONE = BigDecimal.ONE, ONE_HALF = new BigDecimal("1.5");

    // ── objective layer ─────────────────────────────────────────────────

    @Test
    @DisplayName("MCQ is exact: the single pick must be the correct option")
    void mcq() {
        assertThat(EvaluationRules.mcq(List.of(1), List.of(1))).isEqualTo(1.0);
        assertThat(EvaluationRules.mcq(List.of(1), List.of(2))).isZero();
        assertThat(EvaluationRules.mcq(List.of(1), List.of(1, 2))).as("two picks").isZero();
        assertThat(EvaluationRules.mcq(List.of(1), List.of())).isZero();
    }

    @Test
    @DisplayName("MSQ: +1 per correct pick, -1 per wrong pick, floor 0, scaled to the correct options")
    void msq() {
        assertThat(EvaluationRules.msq(List.of(0, 2), List.of(0, 2))).isEqualTo(1.0);
        assertThat(EvaluationRules.msq(List.of(0, 2), List.of(0))).isEqualTo(0.5);
        assertThat(EvaluationRules.msq(List.of(0, 2), List.of(0, 2, 1))).isEqualTo(0.5);
        assertThat(EvaluationRules.msq(List.of(0, 2), List.of(1, 3))).as("floor 0").isZero();
        assertThat(EvaluationRules.msq(List.of(0, 2), List.of(0, 1, 2, 3))).as("ticking all").isZero();
    }

    @Test
    @DisplayName("LOGIC: trimmed and case-folded; numbers equal within 1e-6")
    void logic() {
        assertThat(EvaluationRules.logicMatches("Paris", "  paris ")).isTrue();
        assertThat(EvaluationRules.logicMatches("12", "12.0000005")).isTrue();
        assertThat(EvaluationRules.logicMatches("12", "12.01")).isFalse();
        assertThat(EvaluationRules.logicMatches("12", "twelve")).isFalse();
    }

    @Test
    @DisplayName("CODE_OUTPUT: exact after normalising line endings and trailing whitespace; case matters")
    void codeOutput() {
        assertThat(EvaluationRules.codeOutputMatches("None\n[1, 2, 3]\n3", "None  \r\n[1, 2, 3]\r\n3\n\n")).isTrue();
        assertThat(EvaluationRules.codeOutputMatches("None", "none")).isFalse();
        assertThat(EvaluationRules.codeOutputMatches("a b", "a  b")).as("inner spacing matters").isFalse();
    }

    @Test
    @DisplayName("coding correctness is the weighted pass share of HIDDEN tests only")
    void hiddenShare() {
        List<TestOutcome> tests = List.of(
                new TestOutcome(true, "BASIC", ONE, false, "FAILED"),        // visible: ignored
                new TestOutcome(false, "BASIC", ONE, true, "PASSED"),
                new TestOutcome(false, "EDGE", ONE_HALF, false, "FAILED"),
                new TestOutcome(false, "LARGE", ONE_HALF, true, "PASSED"));
        assertThat(EvaluationRules.hiddenShare(tests)).isCloseTo(2.5 / 4.0, within(1e-9));
        assertThat(EvaluationRules.hiddenPassed(tests)).isEqualTo(2);
        assertThat(EvaluationRules.hiddenTotal(tests)).isEqualTo(3);
        assertThat(EvaluationRules.hiddenShare(List.of(new TestOutcome(true, "BASIC", ONE, true, "PASSED"))))
                .as("no hidden tests").isZero();
    }

    // ── stringency ──────────────────────────────────────────────────────

    @Test
    @DisplayName("rubric credit is halved below 50% hidden share and removed below 25%")
    void rubricMultiplier() {
        assertThat(EvaluationRules.rubricMultiplier(0.24, 0.5, 0.25)).isZero();
        assertThat(EvaluationRules.rubricMultiplier(0.25, 0.5, 0.25)).isEqualTo(0.5);
        assertThat(EvaluationRules.rubricMultiplier(0.49, 0.5, 0.25)).isEqualTo(0.5);
        assertThat(EvaluationRules.rubricMultiplier(0.50, 0.5, 0.25)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("reading input is recognised per language, and comments don't count")
    void readsInput() {
        assertThat(EvaluationRules.readsInput("java", "Scanner in = new Scanner(System.in);")).isTrue();
        assertThat(EvaluationRules.readsInput("python", "n = int(input())")).isTrue();
        assertThat(EvaluationRules.readsInput("javascript", "process.stdin.on('data', d => {})")).isTrue();
        assertThat(EvaluationRules.readsInput("cpp", "int a; cin >> a;")).isTrue();
        assertThat(EvaluationRules.readsInput("java", "// Scanner in = new Scanner(System.in);\nSystem.out.println(3);")).isFalse();
    }

    @Test
    @DisplayName("hard-coding: ignoring the input, or embedding two visible expected outputs")
    void hardcoded() {
        String ignores = "public class Main { public static void main(String[] a) { System.out.println(42); } }";
        assertThat(EvaluationRules.isHardcoded("java", ignores, List.of("42"), true, 3)).isTrue();
        assertThat(EvaluationRules.isHardcoded("java", ignores, List.of("42"), false, 3))
                .as("tests give no input, so not reading any is fine").isFalse();

        String embeds = "Scanner s = new Scanner(System.in); String in = s.nextLine();\n"
                + "if (in.equals(\"1 2\")) System.out.println(\"777777\"); else System.out.println(\"123456\");";
        assertThat(EvaluationRules.isHardcoded("java", embeds, List.of("777777", "123456"), true, 3)).isTrue();
        assertThat(EvaluationRules.isHardcoded("java", embeds, List.of("777777"), true, 3)).as("one literal").isFalse();
        assertThat(EvaluationRules.literalOutputMatches("java", "int x = 3; Scanner s;", List.of("3", "7"), 3))
                .as("short outputs are too common to count").isZero();
    }

    @Test
    @DisplayName("no credit for comments, plans or the untouched starter")
    void realCode() {
        String starter = "public class Main {\n  public static void main(String[] a) {\n    // your code\n  }\n}";
        assertThat(EvaluationRules.hasRealCode("java", starter, starter)).isFalse();
        assertThat(EvaluationRules.hasRealCode("java", starter.replace("// your code", "// I would loop and add"), starter)).isFalse();
        assertThat(EvaluationRules.hasRealCode("java", "// plan: read, add, print", "")).isFalse();
        assertThat(EvaluationRules.hasRealCode("java", starter.replace("// your code", "System.out.println(1);"), starter)).isTrue();
        assertThat(EvaluationRules.hasRealCode("python", "# read input\n\"\"\"plan\"\"\"", "")).isFalse();
        assertThat(EvaluationRules.hasRealCode("python", "print(sum(map(int, input().split())))", "")).isTrue();
    }

    // ── rubric consistency & evidence ───────────────────────────────────

    @Test
    @DisplayName("two rubric runs disagree beyond 15% of the question's points; the median of three settles it")
    void consistency() {
        assertThat(EvaluationRules.disagrees(6.0, 3.0, 20, 0.15)).isFalse();   // 3 ≤ 3
        assertThat(EvaluationRules.disagrees(6.0, 2.9, 20, 0.15)).isTrue();
        assertThat(EvaluationRules.median(6.0, 0.0, 3.0)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("evidence must be quoted from the candidate's answer")
    void evidence() {
        String code = "for (int i = 0; i < n; i++)\n    total += a[i];";
        assertThat(EvaluationRules.evidenceFound("total += a[i];", code)).isTrue();
        assertThat(EvaluationRules.evidenceFound("for (int i = 0;  i < n; i++) total += a[i];", code))
                .as("whitespace-insensitive").isTrue();
        assertThat(EvaluationRules.evidenceFound("uses a hash map", code)).isFalse();
        assertThat(EvaluationRules.evidenceFound("i", code)).as("too short to be evidence").isFalse();
        assertThat(EvaluationRules.evidenceFound("", code)).isFalse();
    }

    @Test
    @DisplayName("points and percent round to two places")
    void rounding() {
        assertThat(EvaluationRules.points(0.85, 20)).isEqualByComparingTo("17.00");
        assertThat(EvaluationRules.percent(new BigDecimal("17"), new BigDecimal("40"))).isEqualByComparingTo("42.50");
        assertThat(EvaluationRules.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo("0");
    }
}
