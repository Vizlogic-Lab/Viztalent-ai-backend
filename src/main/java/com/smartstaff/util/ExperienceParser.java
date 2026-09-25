package com.smartstaff.util;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic (no-LLM) years-of-experience extraction from free text.
 *  Used on JD text now (Phase 2, experience range for the Jobs detail page)
 *  and will be reused on resume text later (Phase 3, candidate years-of-
 *  experience estimate) — same "X-Y years" / "X+ years" phrasing shows up
 *  in both. */
@Component
public class ExperienceParser {

    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,2})\\s*(?:-|to|–)\\s*(\\d{1,2})\\s*\\+?\\s*years?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINIMUM = Pattern.compile(
            "(\\d{1,2})\\s*\\+\\s*years?", Pattern.CASE_INSENSITIVE);
    // Resume phrasing: "5 years of experience", "3 yrs exp", "7+ years in...".
    private static final Pattern YEARS_OF_EXPERIENCE = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\s*(?:of\\s+)?(?:experience|exp)?", Pattern.CASE_INSENSITIVE);

    public record Range(Integer minYears, Integer maxYears) {
        static final Range NONE = new Range(null, null);
    }

    public Range extractRange(String text) {
        if (text == null || text.isBlank()) return Range.NONE;
        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            int min = Integer.parseInt(range.group(1));
            int max = Integer.parseInt(range.group(2));
            return min <= max ? new Range(min, max) : new Range(max, min);
        }
        Matcher min = MINIMUM.matcher(text);
        if (min.find()) {
            int minYears = Integer.parseInt(min.group(1));
            return new Range(minYears, null);
        }
        return Range.NONE;
    }

    /** Best single number for "years of experience" on a resume — takes the
     *  MAX of every "N years [of experience]" mention found, since a resume
     *  often states total experience once near the top and may repeat
     *  smaller numbers per job entry; the largest is the best proxy for
     *  total experience without actually parsing a work-history timeline. */
    public int extractYearsOfExperience(String text) {
        if (text == null || text.isBlank()) return 0;
        Matcher m = YEARS_OF_EXPERIENCE.matcher(text);
        int max = 0;
        while (m.find()) {
            int years = Integer.parseInt(m.group(1));
            if (years <= 60) max = Math.max(max, years); // guard against stray large numbers
        }
        return max;
    }
}
