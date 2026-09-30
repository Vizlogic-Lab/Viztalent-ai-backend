package com.smartstaff.service;

import com.smartstaff.dto.response.ScorecardResponse;
import com.smartstaff.dto.response.ScorecardResponse.QuestionScoreView;
import com.smartstaff.entity.QuestionScoreBreakdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScorecardPdfServiceTest {

    private final ScorecardPdfService service = new ScorecardPdfService();

    private ScorecardResponse card(boolean needsReview) {
        var q = new QuestionScoreView("11111111-1111-1111-1111-111111111111", "L1", 0, "CODE_WRITE",
                "java", 15, new BigDecimal("10.50"), true, needsReview, true, 8, 11, "Passed 8/11 tests.",
                List.of(new QuestionScoreBreakdown("approach", 10, 10, "solid")));
        return new ScorecardResponse(true, "SCORED", "22222222-2222-2222-2222-222222222222",
                "cand@example.com", "Cand Idate", List.of("L1"), 1,
                new BigDecimal("62.50"), new BigDecimal("100"), new BigDecimal("62.50"),
                50, true, needsReview, needsReview ? new BigDecimal("4.50") : BigDecimal.ZERO,
                null, Instant.now(), Instant.now(), List.of(q));
    }

    @Test
    @DisplayName("renders a non-empty PDF for a scored card")
    void rendersPdf() {
        byte[] pdf = service.render("Java Backend Developer", card(false));
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("a provisional (needs-review) card still renders")
    void rendersProvisional() {
        byte[] pdf = service.render("Java Backend Developer", card(true));
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(800);
    }
}
