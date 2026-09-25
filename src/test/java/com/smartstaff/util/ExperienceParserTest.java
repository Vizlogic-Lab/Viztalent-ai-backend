package com.smartstaff.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceParserTest {

    private final ExperienceParser parser = new ExperienceParser();

    @Test
    @DisplayName("JD ranges: '3-5 years', '3 to 5 years', reversed bounds")
    void ranges() {
        assertThat(parser.extractRange("Looking for 3-5 years of experience")).isEqualTo(new ExperienceParser.Range(3, 5));
        assertThat(parser.extractRange("3 to 5 years")).isEqualTo(new ExperienceParser.Range(3, 5));
        assertThat(parser.extractRange("5-3 years")).isEqualTo(new ExperienceParser.Range(3, 5));
    }

    @Test
    @DisplayName("JD minimums: '5+ years' is a minimum with no maximum")
    void minimum() {
        assertThat(parser.extractRange("5+ years in backend development")).isEqualTo(new ExperienceParser.Range(5, null));
    }

    @Test
    @DisplayName("no experience mention → no range")
    void noRange() {
        assertThat(parser.extractRange("Great team, great snacks")).isEqualTo(new ExperienceParser.Range(null, null));
        assertThat(parser.extractRange(null)).isEqualTo(new ExperienceParser.Range(null, null));
    }

    @Test
    @DisplayName("resume years: takes the largest 'N years' mention as total experience")
    void resumeYears() {
        assertThat(parser.extractYearsOfExperience("6 years of experience overall. 2 years at Acme, 3 yrs exp at Beta.")).isEqualTo(6);
        assertThat(parser.extractYearsOfExperience("7+ years in Java")).isEqualTo(7);
    }

    @Test
    @DisplayName("resume years: ignores absurd numbers and blank input")
    void resumeYearsGuards() {
        assertThat(parser.extractYearsOfExperience("99 years of experience")).isZero();
        assertThat(parser.extractYearsOfExperience("")).isZero();
        assertThat(parser.extractYearsOfExperience(null)).isZero();
    }
}
