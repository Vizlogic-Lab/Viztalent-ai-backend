package com.smartstaff.service;

import org.apache.tika.Tika;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorecardPdfServiceTest {

    private final ScorecardPdfService service = new ScorecardPdfService();

    private ScorecardPdfService.Card card(String status, String reviewer, String comment) {
        Map<String, BigDecimal> comp = new LinkedHashMap<>();
        comp.put("PROBLEM_SOLVING", new BigDecimal("75.00"));
        comp.put("DEBUGGING", new BigDecimal("40.00"));
        return new ScorecardPdfService.Card("Cand Idate", "cand@example.com", "Java Backend Developer", "JD-0042", "L2", 3,
                Instant.now(), new BigDecimal("82.00"), new BigDecimal("45.00"), new BigDecimal("56.10"),
                "STRONG", "BASIC", "NOT_RECOMMENDED", comp,
                List.of("Q2 (CODE_WRITE): 3.5/10 — passed 5 of 12 hidden tests; failed EDGE: wrong answer"),
                status, reviewer, comment, reviewer == null ? null : Instant.now(), null,
                List.of("THEORY_PRACTICE_GAP"), Map.of("TAB_SWITCH", 2L, "PASTE", 1L), true);
    }

    private static String text(byte[] pdf) throws Exception {
        return new Tika().parseToString(new ByteArrayInputStream(pdf));
    }

    @Test
    @DisplayName("validated card: scores, bands, competencies, evidence, SME stamp and integrity are in the text")
    void validated() throws Exception {
        byte[] pdf = service.render(card("VALIDATED", "Dr SME", "Theory strong, practice weak"));
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
        assertThat(text(pdf)).contains("Cand Idate", "JD-0042", "L2", "82", "45", "STRONG", "BASIC", "NOT RECOMMENDED",
                "PROBLEM SOLVING", "75%", "passed 5 of 12 hidden tests", "VALIDATED", "Dr SME",
                "Theory strong, practice weak", "Tab switches: 2", "Pastes: 1", "Auto-submitted", "THEORY_PRACTICE_GAP");
    }

    @Test
    @DisplayName("provisional card says it isn't validated yet")
    void provisional() throws Exception {
        assertThat(text(service.render(card("PROVISIONAL", null, null)))).contains("PROVISIONAL", "not yet validated");
    }
}
