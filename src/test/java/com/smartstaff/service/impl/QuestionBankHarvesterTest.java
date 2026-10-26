package com.smartstaff.service.impl;

import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.QuestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionBankHarvesterTest {

    private static AssessmentQuestion question(QuestionType type, String prompt) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setType(type);
        q.setPrompt(prompt);
        q.setOrigin("AI");
        q.setValidated(true);
        return q;
    }

    @Test
    @DisplayName("only validated, generic, AI-drafted, non-scenario questions are harvested")
    void eligibility() {
        assertThat(QuestionBankHarvester.eligible(question(QuestionType.MCQ, "p"))).isTrue();
        assertThat(QuestionBankHarvester.eligible(question(QuestionType.CODE_DEBUG, "p"))).isTrue();

        AssessmentQuestion project = question(QuestionType.MCQ, "p");
        project.setProjectSpecific(true);
        assertThat(QuestionBankHarvester.eligible(project)).isFalse();

        assertThat(QuestionBankHarvester.eligible(question(QuestionType.SCENARIO, "p"))).isFalse();

        AssessmentQuestion fromBank = question(QuestionType.MCQ, "p");
        fromBank.setOrigin("BANK");
        assertThat(QuestionBankHarvester.eligible(fromBank)).isFalse();

        AssessmentQuestion unvalidated = question(QuestionType.MCQ, "p");
        unvalidated.setValidated(false);
        assertThat(QuestionBankHarvester.eligible(unvalidated)).isFalse();
    }

    @Test
    @DisplayName("the content hash ignores case and spacing but not the question or its options")
    void hashing() {
        AssessmentQuestion a = question(QuestionType.MCQ, "What  does   GET do?");
        a.setOptions(List.of("Reads", "Writes"));
        AssessmentQuestion b = question(QuestionType.MCQ, "what does get do?");
        b.setOptions(List.of("reads", "writes"));
        AssessmentQuestion c = question(QuestionType.MCQ, "what does get do?");
        c.setOptions(List.of("reads", "deletes"));

        assertThat(QuestionBankHarvester.hash(a)).isEqualTo(QuestionBankHarvester.hash(b));
        assertThat(QuestionBankHarvester.hash(a)).isNotEqualTo(QuestionBankHarvester.hash(c));
    }
}
