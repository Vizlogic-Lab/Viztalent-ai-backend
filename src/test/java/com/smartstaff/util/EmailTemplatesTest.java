package com.smartstaff.util;

import com.smartstaff.util.EmailTemplates.Kind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesTest {

    @Test
    void defaultsAreValid() {
        for (Kind k : Kind.values()) {
            EmailTemplates.Template t = EmailTemplates.defaults(k);
            assertThat(EmailTemplates.problem(k, t.subject(), t.body())).as(k.name()).isNull();
        }
    }

    @Test
    void placeholdersAreCheckedPerKind() {
        assertThat(EmailTemplates.problem(Kind.REMINDER, "Hi", "Use {link}")).contains("{link} can't be used here");
        assertThat(EmailTemplates.problem(Kind.REJECTION, "Hi {expires_at}", "x")).contains("{expires_at}");
        assertThat(EmailTemplates.problem(Kind.ASSESSMENT_INVITE, "Hi", "No link here")).contains("must contain {link}");
        assertThat(EmailTemplates.problem(Kind.ASSESSMENT_INVITE, "Hi {nme}", "{link}")).contains("{nme}");
        assertThat(EmailTemplates.problem(Kind.INTERVIEW_INVITE, "Two\nlines", "{link}")).contains("single line");
        assertThat(EmailTemplates.problem(Kind.REJECTION, " ", "x")).contains("subject is required");
        assertThat(EmailTemplates.problem(Kind.REJECTION, "x", "x".repeat(10_001))).contains("at most");
    }

    @Test
    void renderFillsKnownPlaceholdersOnly() {
        Map<String, String> v = Map.of("candidate_name", "Asha\nRao", "role", "Dev $1", "link", "https://x/a?token=a$b");
        assertThat(EmailTemplates.render("Hi {candidate_name}, {role}: {link} {other}", v, false))
                .isEqualTo("Hi Asha\nRao, Dev $1: https://x/a?token=a$b {other}");
        assertThat(EmailTemplates.render("{candidate_name} for {role}", v, true)).isEqualTo("Asha Rao for Dev $1");
    }
}
