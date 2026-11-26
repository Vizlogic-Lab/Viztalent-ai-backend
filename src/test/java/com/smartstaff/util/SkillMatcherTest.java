package com.smartstaff.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMatcherTest {

    private static final String CV = """
            PMO professional. Maintained RAID logs and risk registers; served as primary coordinator
            across vendors and Director-level stakeholders. Tools: Jira, Confluence, MS-Project.
            Built CI/CD pipelines in C++ and .NET.
            """;

    @Test
    void matchesNameAsWholePhrase() {
        assertThat(SkillMatcher.matches(CV, "jira", null)).isTrue();
        assertThat(SkillMatcher.matches(CV, "risk register", null)).isTrue();   // plural in CV
        assertThat(SkillMatcher.matches(CV, "ms project", null)).isTrue();      // hyphen in CV
        assertThat(SkillMatcher.matches(CV, "ci/cd", null)).isTrue();
        assertThat(SkillMatcher.matches(CV, "c++", null)).isTrue();
        assertThat(SkillMatcher.matches(CV, ".net", null)).isTrue();
        assertThat(SkillMatcher.matches(CV, "sap", null)).isFalse();
        assertThat(SkillMatcher.matches(CV, "java", null)).isFalse();
        assertThat(SkillMatcher.matches("Javascript developer", "java", null)).isFalse(); // not part of a longer word
    }

    @Test
    void matchesAliases() {
        Map<String, List<String>> aliases = Map.of(
                "stakeholder management", List.of("stakeholders", "stakeholder communication"),
                "status reporting", List.of("status reports"));
        assertThat(SkillMatcher.matches(CV, "stakeholder management", aliases)).isTrue();
        assertThat(SkillMatcher.matches(CV, "status reporting", aliases)).isFalse();
        assertThat(SkillMatcher.matches(CV, "Stakeholder Management", aliases)).isTrue(); // skill case ignored
    }

    @Test
    void singularAndPluralMatchEitherWay() {
        String cv = "Authored RCA documents for internal escalation use; mitigated infrastructure dependencies; ran batches.";
        assertThat(SkillMatcher.matches(cv, "escalation management", Map.of("escalation management", List.of("escalations")))).isTrue();
        assertThat(SkillMatcher.matches(cv, "infrastructure dependency", null)).isTrue();
        assertThat(SkillMatcher.matches("one infrastructure dependency", "infrastructure dependencies", null)).isTrue();
        assertThat(SkillMatcher.matches(cv, "batch", null)).isTrue();
        // Words that only end in "s" are not cut down.
        assertThat(SkillMatcher.matches("risk analysis", "analysis", null)).isTrue();
        assertThat(SkillMatcher.matches("risk analys", "analysis", null)).isFalse();
        assertThat(SkillMatcher.matches("deployed on aws", "aws", null)).isTrue();
        assertThat(SkillMatcher.matches("the aw team", "aws", null)).isFalse();
        assertThat(SkillMatcher.matches("good process", "process", null)).isTrue();
    }

    @Test
    void blankInputs() {
        assertThat(SkillMatcher.matches(null, "jira", null)).isFalse();
        assertThat(SkillMatcher.matches(CV, " ", null)).isFalse();
    }
}
