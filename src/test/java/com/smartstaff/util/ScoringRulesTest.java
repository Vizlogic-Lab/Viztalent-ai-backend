package com.smartstaff.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoringRulesTest {

    @Test
    @DisplayName("MCQ is all-or-nothing on the single correct option")
    void mcq() {
        assertThat(ScoringRules.mcq(List.of(1), List.of(1))).isEqualTo(1.0);
        assertThat(ScoringRules.mcq(List.of(1), List.of(0))).isEqualTo(0.0);
        assertThat(ScoringRules.mcq(List.of(1), List.of())).isEqualTo(0.0);
        assertThat(ScoringRules.mcq(List.of(1), List.of(0, 1))).as("two picks on an MCQ is wrong").isEqualTo(0.0);
    }

    @Test
    @DisplayName("MSQ gives (right - wrong) / number-correct, clamped, so full needs the exact set")
    void msq() {
        // correct = {0,2}. Exactly right -> 1.
        assertThat(ScoringRules.msq(List.of(0, 2), List.of(0, 2))).isEqualTo(1.0);
        // One of two right, none wrong -> 0.5.
        assertThat(ScoringRules.msq(List.of(0, 2), List.of(0))).isEqualTo(0.5);
        // Both right + one wrong -> (2-1)/2 = 0.5.
        assertThat(ScoringRules.msq(List.of(0, 2), List.of(0, 2, 1))).isEqualTo(0.5);
        // One right + one wrong -> (1-1)/2 = 0, clamped.
        assertThat(ScoringRules.msq(List.of(0, 2), List.of(1))).isEqualTo(0.0);
        // Ticking everything is penalised below full.
        assertThat(ScoringRules.msq(List.of(0, 2), List.of(0, 1, 2, 3))).isLessThan(1.0);
    }

    @Test
    @DisplayName("text answers match case-insensitively after whitespace normalisation")
    void text() {
        assertThat(ScoringRules.textMatches("12", " 12 ")).isTrue();
        assertThat(ScoringRules.textMatches("Paris", "paris")).isTrue();
        assertThat(ScoringRules.textMatches("12", "13")).isFalse();
    }

    @Test
    @DisplayName("CODE_WRITE combines 70% tests + 30% rubric; rubric withheld leaves only the test share")
    void codeWrite() {
        assertThat(ScoringRules.codeWrite(1.0, 1.0, true)).isEqualTo(1.0);
        assertThat(ScoringRules.codeWrite(1.0, 0.0, true)).isCloseTo(0.70, within(1e-9));
        assertThat(ScoringRules.codeWrite(0.0, 1.0, true)).isCloseTo(0.30, within(1e-9));
        assertThat(ScoringRules.codeWrite(1.0, 1.0, false)).as("rubric ungraded -> tests only").isCloseTo(0.70, within(1e-9));
        assertThat(ScoringRules.codeWrite(0.5, 1.0, true)).isCloseTo(0.35 + 0.30, within(1e-9));
    }

    @Test
    @DisplayName("points and percent round to two places")
    void pointsAndPercent() {
        assertThat(ScoringRules.points(0.7, 15)).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(ScoringRules.percent(new BigDecimal("10.50"), new BigDecimal("100"))).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(ScoringRules.percent(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
