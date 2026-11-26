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
    @DisplayName("JD range: 'several years of experience' reads as at least 3")
    void severalYears() {
        assertThat(parser.extractRange("Several years of professional experience in project management"))
                .isEqualTo(new ExperienceParser.Range(3, null));
        assertThat(parser.extractRange("We have grown for several years.")).isEqualTo(new ExperienceParser.Range(null, null));
    }

    @Test
    @DisplayName("resume years: numbers not tied to experience are ignored")
    void resumeYearsNeedsExperienceContext() {
        java.time.YearMonth now = java.time.YearMonth.of(2026, 11);
        assertThat(parser.extractYearsOfExperience("Product carries a 10 years warranty.", now)).isZero();
        assertThat(parser.extractYearsOfExperience("Delivered the migration within 2 years.", now)).isZero();
        assertThat(parser.extractYearsOfExperience("Experience: 6.5 years in Java", now)).isEqualTo(6);
        assertThat(parser.extractYearsOfExperience("Total experience - 4 yrs", now)).isEqualTo(4);
        assertThat(parser.extractYearsOfExperience("8 years as a backend developer", now)).isEqualTo(8);
        assertThat(parser.extractYearsOfExperience("5 years of professional experience", now)).isEqualTo(5);
    }

    @Test
    @DisplayName("resume years: falls back to work-history dates, merging overlaps, skipping education")
    void resumeYearsFromDates() {
        java.time.YearMonth now = java.time.YearMonth.of(2026, 11);
        String cv = """
                Software Engineer, Acme   Jan 2019 - Present
                Intern, Beta              Jun 2018 – Dec 2018
                B.Tech, XYZ University    2014 - 2018
                """;
        // Jun 2018 -> Dec 2018 (6 months) + Jan 2019 -> Nov 2026 (94 months) = 100 months = 8 years
        assertThat(parser.extractYearsOfExperience(cv, now)).isEqualTo(8);
        // Overlapping jobs are not double-counted: 2020-2024 and 2022-2026 = 2020..2026 = 6 years
        assertThat(parser.extractYearsOfExperience("Acme 2020 - 2024\nBeta 2022 - 2026", now)).isEqualTo(6);
        assertThat(parser.extractYearsOfExperience("Developer 06/2021 to till date", now)).isEqualTo(5);
        // A stated total wins over dates
        assertThat(parser.extractYearsOfExperience("3 years of experience\nAcme 2015 - Present", now)).isEqualTo(3);
        // Only education dates -> not found
        assertThat(parser.extractYearsOfExperience("MCA, ABC College 2022 - 2024", now)).isZero();
    }

    @Test
    @DisplayName("resume years: a headline ending with the total, and 'worked N years'")
    void resumeYearsHeadlineAndWorked() {
        java.time.YearMonth now = java.time.YearMonth.of(2026, 11);
        String daniel = """
                Daniel Shahid
                daniel.test@example.com
                Contact Center Consultant - 11.5 years
                Worked 6 years on Genesys PureCloud and Genesys Cloud CX.
                """;
        assertThat(parser.extractYearsOfExperience(daniel, now)).isEqualTo(11);
        assertThat(parser.extractYearsOfExperience("Priya Nair\nContact Centre Engineer - 5 years\nAvaya and Cisco.", now)).isEqualTo(5);
        assertThat(parser.extractYearsOfExperience("Java Developer | 7 yrs", now)).isEqualTo(7);
        assertThat(parser.extractYearsOfExperience("Senior QA Engineer (8+ years)", now)).isEqualTo(8);
        assertThat(parser.extractYearsOfExperience("Worked 6 years on Genesys PureCloud", now)).isEqualTo(6);
        assertThat(parser.extractYearsOfExperience("Working for over 4 years with SAP BW.", now)).isEqualTo(4);
        assertThat(parser.extractYearsOfExperience("Contact Center Consultant - 11.5 years\r\nNext line", now)).isEqualTo(11);
    }

    @Test
    @DisplayName("resume years: age, education and in-sentence numbers are not experience")
    void resumeYearsHeadlineGuards() {
        java.time.YearMonth now = java.time.YearMonth.of(2026, 11);
        assertThat(parser.extractYearsOfExperience("Age: 29 years", now)).isZero();
        assertThat(parser.extractYearsOfExperience("Date of birth - 32 years", now)).isZero();
        assertThat(parser.extractYearsOfExperience("B.Tech - 4 years", now)).isZero();
        assertThat(parser.extractYearsOfExperience("The project ran for 3 years and won an award.", now)).isZero();
        assertThat(parser.extractYearsOfExperience("Warranty support - 2 years. Customers renewed every year after that.", now)).isZero();
    }

    @Test
    @DisplayName("resume years: ignores absurd numbers and blank input")
    void resumeYearsGuards() {
        assertThat(parser.extractYearsOfExperience("99 years of experience")).isZero();
        assertThat(parser.extractYearsOfExperience("")).isZero();
        assertThat(parser.extractYearsOfExperience(null)).isZero();
    }
}
