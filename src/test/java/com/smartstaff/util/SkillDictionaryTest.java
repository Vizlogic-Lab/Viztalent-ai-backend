package com.smartstaff.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillDictionaryTest {

    private SkillDictionary dictionary;

    @BeforeEach
    void load() {
        dictionary = new SkillDictionary();
        dictionary.load(); // normally invoked by @PostConstruct
    }

    @Test
    @DisplayName("finds skills case-insensitively, including multi-word ones")
    void extractsCaseInsensitively() {
        assertThat(dictionary.extract("We use JAVA with Spring Boot and postgreSQL."))
                .contains("java", "spring boot", "postgresql");
    }

    @Test
    @DisplayName("matches on word boundaries — 'JavaScript' is not 'Java'")
    void wordBoundaries() {
        assertThat(dictionary.extract("Strong JavaScript background")).contains("javascript").doesNotContain("java");
    }

    @Test
    @DisplayName("handles skills made of symbols (C++, CI/CD, Node.js)")
    void symbolSkills() {
        assertThat(dictionary.extract("C++ services, a CI/CD pipeline and Node.js tooling"))
                .contains("c++", "ci/cd", "node.js");
    }

    @Test
    @DisplayName("blank input yields nothing")
    void blankInput() {
        assertThat(dictionary.extract("")).isEmpty();
        assertThat(dictionary.extract(null)).isEmpty();
        assertThat(dictionary.classify("   ").mustHave()).isEmpty();
    }

    @Test
    @DisplayName("skills are must-have by default")
    void mustHaveByDefault() {
        var result = dictionary.classify("You will work with Java and PostgreSQL.");
        assertThat(result.mustHave()).contains("java", "postgresql");
        assertThat(result.niceToHave()).isEmpty();
    }

    @Test
    @DisplayName("a trailing qualifier ('is a plus') makes only that clause's skill nice-to-have")
    void trailingQualifier() {
        var result = dictionary.classify("Required: Docker, Linux. Kubernetes experience is a plus.");
        assertThat(result.mustHave()).contains("docker", "linux");
        assertThat(result.niceToHave()).contains("kubernetes");
    }

    @Test
    @DisplayName("regression: a qualifier in the NEXT sentence must not bleed back onto an earlier skill")
    void qualifierDoesNotBleedAcrossSentences() {
        var result = dictionary.classify("Required: Docker, Linux. Kubernetes is a plus.");
        assertThat(result.niceToHave()).doesNotContain("linux", "docker");
    }

    @Test
    @DisplayName("a leading qualifier ('Preferred: ...') also works")
    void leadingQualifier() {
        var result = dictionary.classify("Must know Java.\nPreferred: Terraform\n");
        assertThat(result.mustHave()).contains("java");
        assertThat(result.niceToHave()).contains("terraform");
    }
}
