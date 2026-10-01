package com.smartstaff.util;

import com.smartstaff.entity.ReviewPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRulesTest {

    @Test
    @DisplayName("sampling is deterministic per attempt id and close to the rate overall")
    void samplingDeterministic() {
        Random rnd = new Random(42);
        int in = 0;
        for (int i = 0; i < 4000; i++) {
            UUID id = new UUID(rnd.nextLong(), rnd.nextLong());
            boolean first = ReviewRules.sampled(id, 0.25);
            for (int k = 0; k < 3; k++) assertThat(ReviewRules.sampled(UUID.fromString(id.toString()), 0.25)).isEqualTo(first);
            if (first) in++;
            // A higher rate keeps everything a lower rate selected.
            if (first) assertThat(ReviewRules.sampled(id, 0.5)).isTrue();
        }
        assertThat(in / 4000.0).isBetween(0.22, 0.28);
        UUID any = UUID.randomUUID();
        assertThat(ReviewRules.sampled(any, 0)).isFalse();
        assertThat(ReviewRules.sampled(any, 1)).isTrue();
    }

    @Test
    @DisplayName("PROCEED and HR_REVIEW always reviewed; NOT_RECOMMENDED only by theory threshold or sample")
    void needsReview() {
        UUID id = UUID.randomUUID();
        ReviewPolicy none = new ReviewPolicy(false, 0, 70);
        assertThat(ReviewRules.needsReview("PROCEED", BigDecimal.ZERO, id, none)).isTrue();
        assertThat(ReviewRules.needsReview("HR_REVIEW", null, id, none)).isTrue();
        assertThat(ReviewRules.needsReview("NOT_RECOMMENDED", BigDecimal.valueOf(69.99), id, none)).isFalse();
        assertThat(ReviewRules.needsReview("NOT_RECOMMENDED", BigDecimal.valueOf(70), id, none)).isTrue();
        assertThat(ReviewRules.needsReview("NOT_RECOMMENDED", null, id, new ReviewPolicy(false, 1, 101))).isTrue();
    }

    @Test
    @DisplayName("due date = +2 working days, skipping the weekend")
    void workingDays() {
        Instant thu = Instant.parse("2026-10-01T10:00:00Z");
        assertThat(ReviewRules.addWorkingDays(thu, 2)).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));   // Mon
        Instant sat = Instant.parse("2026-10-03T10:00:00Z");
        Instant due = ReviewRules.addWorkingDays(sat, 2);
        assertThat(due.atZone(ZoneOffset.UTC).getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    @DisplayName("suggestions rank by summed skill weight, case-insensitive")
    void rank() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, List<String>> skills = new LinkedHashMap<>();
        skills.put(a, List.of("python"));
        skills.put(b, List.of("Java", "SPRING", "java"));
        skills.put(c, List.of("sql"));
        Map<String, Integer> weights = Map.of("java", 5, "Spring", 3, "SQL", 2);
        List<ReviewRules.Suggestion> out = ReviewRules.rank(skills, weights);
        assertThat(out).extracting(ReviewRules.Suggestion::userId).containsExactly(b, c, a);
        assertThat(out.get(0).score()).isEqualTo(8);
        assertThat(out.get(0).matched()).containsExactly("Java", "SPRING");
        assertThat(out.get(2).score()).isZero();
    }
}
