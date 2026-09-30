package com.smartstaff.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OutputComparatorTest {

    @Test
    void ignoresTrailingWhitespaceLineEndingsAndTrailingBlankLines() {
        assertThat(OutputComparator.matches("1 2 3\n4", "1 2 3   \r\n4\n\n", null)).isTrue();
        assertThat(OutputComparator.matches("hello", "hello\n", null)).isTrue();
        assertThat(OutputComparator.matches("", "\n\n", null)).isTrue();
    }

    @Test
    void leadingWhitespaceAndInnerDifferencesStillCount() {
        assertThat(OutputComparator.matches("1 2", " 1 2", null)).isFalse();
        assertThat(OutputComparator.matches("1 2", "1  2", null)).isFalse();
        assertThat(OutputComparator.matches("a\nb", "a\n\nb", null)).isFalse();
        assertThat(OutputComparator.matches("3", "4", null)).isFalse();
        assertThat(OutputComparator.matches("3", null, null)).isFalse();
    }

    @Test
    void floatToleranceComparesNumericTokens() {
        BigDecimal tol = new BigDecimal("0.000001");
        assertThat(OutputComparator.matches("0.3", "0.30000000000000004", tol)).isTrue();
        assertThat(OutputComparator.matches("area 3.1415926", "area 3.1415929", tol)).isTrue();
        assertThat(OutputComparator.matches("0.3", "0.31", tol)).isFalse();
        assertThat(OutputComparator.matches("area 3.14", "size 3.14", tol)).isFalse();
        assertThat(OutputComparator.matches("1 2", "1 2 3", tol)).isFalse();
        assertThat(OutputComparator.matches("NaN", "NaN", tol)).as("identical tokens match").isTrue();
        assertThat(OutputComparator.matches("1.0", "NaN", tol)).isFalse();
        assertThat(OutputComparator.matches("0.3", "0.30000000000000004", null)).as("no tolerance = exact").isFalse();
    }
}
