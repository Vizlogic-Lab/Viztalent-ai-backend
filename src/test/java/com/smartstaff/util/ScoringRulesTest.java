package com.smartstaff.util;

import com.smartstaff.entity.Competency;
import com.smartstaff.entity.Dimension;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.entity.ScoringSettings;
import com.smartstaff.util.ScoringRules.Result;
import com.smartstaff.util.ScoringRules.Scored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringRulesTest {

    private static Scored theory(double earned, int points) {
        return new Scored(QuestionType.MCQ, Dimension.THEORY, Competency.CONCEPTS, earned, points);
    }

    private static Scored handsOn(double earned, int points) {
        return new Scored(QuestionType.CODE_WRITE, Dimension.HANDS_ON, Competency.CODING, earned, points);
    }

    private static Result score(double t, double h) {
        return ScoringRules.score(List.of(theory(t, 100), handsOn(h, 100)), null, Set.of(), 0);
    }

    @Test
    @DisplayName("architecture-doc sample: theory 82, hands-on 45 → NOT_RECOMMENDED with THEORY_PRACTICE_GAP")
    void docSample() {
        Result r = ScoringRules.score(List.of(theory(8.2, 10), handsOn(4.5, 10)), null, Set.of(), 0);
        assertThat(r.theory()).isEqualByComparingTo("82");
        assertThat(r.handsOn()).isEqualByComparingTo("45");
        assertThat(r.overall()).isEqualByComparingTo("56.10");   // 0.3×82 + 0.7×45
        assertThat(r.theoryBand()).isEqualTo("STRONG");
        assertThat(r.handsOnBand()).isEqualTo("BASIC");
        assertThat(r.recommendation()).isEqualTo("NOT_RECOMMENDED");
        assertThat(r.flags()).containsExactly("THEORY_PRACTICE_GAP");
    }

    @Test
    @DisplayName("scores are points earned ÷ possible per dimension; competencies sub-scored")
    void pointsWeighted() {
        Result r = ScoringRules.score(List.of(theory(2, 4), theory(6, 6), handsOn(3, 10),
                new Scored(QuestionType.CODE_DEBUG, Dimension.HANDS_ON, Competency.DEBUGGING, 10, 10)), null, Set.of(), 0);
        assertThat(r.theory()).isEqualByComparingTo("80");
        assertThat(r.handsOn()).isEqualByComparingTo("65");
        assertThat(r.competencies()).containsOnlyKeys("CONCEPTS", "CODING", "DEBUGGING");
        assertThat(r.competencies().get("CODING")).isEqualByComparingTo("30");
        assertThat(r.competencies().get("DEBUGGING")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("bands: 0-39, 40-59, 60-79, 80-100")
    void bands() {
        assertThat(ScoringRules.band(new BigDecimal("39.99"))).isEqualTo("NOT_DEMONSTRATED");
        assertThat(ScoringRules.band(new BigDecimal("40"))).isEqualTo("BASIC");
        assertThat(ScoringRules.band(new BigDecimal("59.99"))).isEqualTo("BASIC");
        assertThat(ScoringRules.band(new BigDecimal("60"))).isEqualTo("WORKING");
        assertThat(ScoringRules.band(new BigDecimal("80"))).isEqualTo("STRONG");
        assertThat(ScoringRules.band(null)).isNull();
    }

    @Test
    @DisplayName("gates: PROCEED at hands-on ≥ 60 and theory ≥ 50; HR_REVIEW at hands-on 50-59; else NOT_RECOMMENDED")
    void gates() {
        assertThat(score(50, 60).recommendation()).isEqualTo("PROCEED");
        assertThat(score(49, 90).recommendation()).isEqualTo("NOT_RECOMMENDED");
        assertThat(score(90, 59).recommendation()).isEqualTo("HR_REVIEW");
        assertThat(score(90, 50).recommendation()).isEqualTo("HR_REVIEW");
        assertThat(score(60, 49).recommendation()).isEqualTo("NOT_RECOMMENDED");
    }

    @Test
    @DisplayName("grading-uncertainty flags and > 5 tab switches force HR_REVIEW")
    void reviewTriggers() {
        for (String f : List.of("GRADER_DISAGREEMENT", "HARDCODED", "RUBRIC_UNAVAILABLE")) {
            assertThat(ScoringRules.score(List.of(theory(90, 100), handsOn(90, 100)), null, Set.of(f), 0).recommendation())
                    .as(f).isEqualTo("HR_REVIEW");
        }
        assertThat(ScoringRules.score(List.of(theory(90, 100), handsOn(90, 100)), null, Set.of(), 5).recommendation())
                .isEqualTo("PROCEED");
        Result tabs = ScoringRules.score(List.of(theory(10, 100), handsOn(10, 100)), null, Set.of(), 6);
        assertThat(tabs.recommendation()).isEqualTo("HR_REVIEW");
        assertThat(tabs.flags()).contains("EXCESSIVE_TAB_SWITCHES");
    }

    @Test
    @DisplayName("gap flag at exactly 25 points, not at 24")
    void gapBoundary() {
        assertThat(score(85, 60).flags()).contains("THEORY_PRACTICE_GAP");
        assertThat(score(84, 60).flags()).doesNotContain("THEORY_PRACTICE_GAP");
    }

    @Test
    @DisplayName("per-job settings change weights and thresholds")
    void jobSettings() {
        ScoringSettings s = new ScoringSettings(0.5, 0.5, 70, 40, 55, 2, 30);
        Result r = ScoringRules.score(List.of(theory(40, 100), handsOn(65, 100)), s, Set.of(), 0);
        assertThat(r.overall()).isEqualByComparingTo("52.50");
        assertThat(r.recommendation()).isEqualTo("HR_REVIEW");   // 55 ≤ 65 < 70
        assertThat(ScoringRules.score(List.of(theory(40, 100), handsOn(70, 100)), s, Set.of(), 0).recommendation())
                .isEqualTo("PROCEED");
        assertThat(ScoringRules.score(List.of(theory(90, 100), handsOn(90, 100)), s, Set.of(), 3).recommendation())
                .isEqualTo("HR_REVIEW");
        assertThat(new ScoringSettings(0.5, 0.6, 60, 50, 50, 5, 25).problem()).isNotNull();
        assertThat(ScoringSettings.DEFAULTS.problem()).isNull();
    }

    @Test
    @DisplayName("non-technical roles: hands-on is scenario/logic only")
    void nonTechnical() {
        assertThat(ScoringRules.dimensionFor(QuestionType.SCENARIO, Dimension.HANDS_ON, false)).isEqualTo(Dimension.HANDS_ON);
        assertThat(ScoringRules.dimensionFor(QuestionType.LOGIC, Dimension.HANDS_ON, false)).isEqualTo(Dimension.HANDS_ON);
        assertThat(ScoringRules.dimensionFor(QuestionType.CODE_OUTPUT, Dimension.HANDS_ON, false)).isEqualTo(Dimension.THEORY);
        assertThat(ScoringRules.dimensionFor(QuestionType.CODE_OUTPUT, Dimension.HANDS_ON, true)).isEqualTo(Dimension.HANDS_ON);
    }

    @Test
    @DisplayName("a level with only one dimension: overall = that score, missing gate doesn't block")
    void oneDimension() {
        Result r = ScoringRules.score(List.of(handsOn(70, 100)), null, Set.of(), 0);
        assertThat(r.theory()).isNull();
        assertThat(r.overall()).isEqualByComparingTo("70");
        assertThat(r.recommendation()).isEqualTo("PROCEED");
    }
}
