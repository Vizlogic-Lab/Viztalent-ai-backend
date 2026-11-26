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
    private static final Pattern SEVERAL_YEARS = Pattern.compile(
            "\\b(?:several|multiple)\\s+years\\b[^.\\n]{0,40}?\\bexperience", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINIMUM = Pattern.compile(
            "(\\d{1,2})\\s*\\+\\s*years?", Pattern.CASE_INSENSITIVE);
    // Resume phrasing: "5 years of experience", "3 yrs exp", "7+ years in Java", "8 years as a ...".
    // The number must be tied to experience: a "+", or an experience word right after it.
    // So "10 years warranty" or "within 2 years" are not read as experience.
    private static final Pattern YEARS_OF_EXPERIENCE = Pattern.compile(
            "(\\d{1,2}(?:\\.\\d)?)\\s*(\\+)?\\s*(?:years?|yrs?)\\b\\s*(?:'|’)?\\s*(?:of\\s+)?"
                    + "((?:professional\\s+|relevant\\s+|total\\s+|overall\\s+|industry\\s+|work\\s+|hands[- ]on\\s+)*"
                    + "(?:experience|exp\\b|experienced)|in\\b|as\\b|into\\b|working\\b|of\\s+work)?",
            Pattern.CASE_INSENSITIVE);
    // "Experience: 5 years", "Total experience - 6.5 yrs", "Experience of 4 years".
    private static final Pattern EXPERIENCE_THEN_YEARS = Pattern.compile(
            "experience[^\\d\\n]{0,20}?(\\d{1,2}(?:\\.\\d)?)\\s*\\+?\\s*(?:years?|yrs?)\\b", Pattern.CASE_INSENSITIVE);

    // A headline that ends with the total: "Contact Center Consultant - 11.5 years",
    // "Java Developer | 7 yrs", "Senior QA Engineer (8+ years)". Only at the end of a short line,
    // after a separator; education, age and birth-date lines are skipped (see statedYears).
    private static final Pattern HEADLINE_YEARS = Pattern.compile(
            "^(?=[^\\n]{3,90}$)[^\\n]*?[-–—|:,(]\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s*\\+?\\s*(?:years?|yrs?)\\.?\\s*\\)?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // "Worked 6 years on Genesys", "working for over 4 years with SAP".
    private static final Pattern WORKED_YEARS = Pattern.compile(
            "\\bwork(?:ed|ing)\\s+(?:for\\s+)?(?:over\\s+|about\\s+|around\\s+|nearly\\s+|almost\\s+)?"
                    + "(\\d{1,2}(?:\\.\\d{1,2})?)\\s*\\+?\\s*(?:years?|yrs?)\\b",
            Pattern.CASE_INSENSITIVE);
    // Lines that state an age, not experience ("Age: 29 years").
    private static final Pattern AGE_LINE = Pattern.compile(
            "\\b(age|aged|dob|d\\.o\\.b|date\\s+of\\s+birth|born)\\b", Pattern.CASE_INSENSITIVE);

    private static final String MONTH =
            "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    // Work-history dates: "Jan 2019 - Mar 2024", "2019 – Present", "06/2018 to till date".
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?:(" + MONTH + ")\\.?\\s*,?\\s*|(\\d{1,2})\\s*[/.-]\\s*)?((?:19|20)\\d{2})"
                    + "\\s*(?:-|–|—|to|till|until)\\s*"
                    + "(?:(?:(" + MONTH + ")\\.?\\s*,?\\s*|(\\d{1,2})\\s*[/.-]\\s*)?((?:19|20)\\d{2})"
                    + "|(present|current|now|till\\s+date|to\\s+date|date|ongoing))",
            Pattern.CASE_INSENSITIVE);
    // Date ranges on these lines are study, not work.
    private static final Pattern EDUCATION_LINE = Pattern.compile(
            "\\b(b\\.?\\s?tech|m\\.?\\s?tech|b\\.?e\\b|m\\.?e\\b|b\\.?sc|m\\.?sc|bca|mca|mba|bachelor|master|degree|diploma|"
                    + "university|college|school|education|cgpa|gpa|percentage|graduat|ssc|hsc|intermediate|10th|12th|ph\\.?d)",
            Pattern.CASE_INSENSITIVE);

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
        // "Several years of experience" gives no number; read it as at least 3.
        if (SEVERAL_YEARS.matcher(text).find()) return new Range(3, null);
        return Range.NONE;
    }

    /** Best single number for "years of experience" on a resume; 0 means "not found".
     *  1. The largest stated "N years of experience" / "Experience: N years" (a resume
     *     usually states the total once and repeats smaller per-job numbers).
     *  2. Otherwise, the work-history date ranges ("2019 – Present"), with overlaps merged
     *     and education lines skipped.
     *  Fractions round down ("4.5 years" = 4). */
    public int extractYearsOfExperience(String text) {
        return extractYearsOfExperience(text, java.time.YearMonth.now());
    }

    /** Same, with "today" fixed — for tests. */
    public int extractYearsOfExperience(String text, java.time.YearMonth today) {
        if (text == null || text.isBlank()) return 0;
        int stated = statedYears(text);
        if (stated > 0) return stated;
        return yearsFromDates(text, today);
    }

    private static int statedYears(String text) {
        double max = 0;
        Matcher m = YEARS_OF_EXPERIENCE.matcher(text);
        while (m.find()) {
            boolean plus = m.group(2) != null;
            boolean context = m.group(3) != null;
            if (!plus && !context) continue;
            double years = Double.parseDouble(m.group(1));
            if (years <= 50) max = Math.max(max, years); // guard against stray large numbers
        }
        Matcher e = EXPERIENCE_THEN_YEARS.matcher(text);
        while (e.find()) {
            double years = Double.parseDouble(e.group(1));
            if (years <= 50) max = Math.max(max, years);
        }
        Matcher w = WORKED_YEARS.matcher(text);
        while (w.find()) {
            double years = Double.parseDouble(w.group(1));
            if (years <= 50) max = Math.max(max, years);
        }
        Matcher h = HEADLINE_YEARS.matcher(text);
        while (h.find()) {
            String line = h.group();
            if (EDUCATION_LINE.matcher(line).find() || AGE_LINE.matcher(line).find()) continue;
            double years = Double.parseDouble(h.group(1));
            if (years <= 50) max = Math.max(max, years);
        }
        return (int) Math.floor(max);
    }

    private static int yearsFromDates(String text, java.time.YearMonth today) {
        java.util.List<int[]> spans = new java.util.ArrayList<>(); // [startMonthIndex, endMonthIndex]
        int nowIdx = today.getYear() * 12 + today.getMonthValue() - 1;
        for (String line : text.split("\\R")) {
            if (EDUCATION_LINE.matcher(line).find()) continue;
            Matcher m = DATE_RANGE.matcher(line);
            while (m.find()) {
                int startYear = Integer.parseInt(m.group(3));
                int startMonth = month(m.group(1), m.group(2), 1);
                int start = startYear * 12 + startMonth - 1;
                int end;
                if (m.group(7) != null) {
                    end = nowIdx;
                } else {
                    int endYear = Integer.parseInt(m.group(6));
                    // A bare "2019 - 2021" counts to the end of 2021's start month; with no months, Jan-Jan.
                    end = endYear * 12 + month(m.group(4), m.group(5), 1) - 1;
                }
                if (startYear < 1970 || start > nowIdx || end < start) continue;
                spans.add(new int[] { start, Math.min(end, nowIdx) });
            }
        }
        if (spans.isEmpty()) return 0;
        spans.sort(java.util.Comparator.comparingInt(s -> s[0]));
        int months = 0;
        int curStart = spans.get(0)[0], curEnd = spans.get(0)[1];
        for (int[] s : spans.subList(1, spans.size())) {
            if (s[0] <= curEnd) {
                curEnd = Math.max(curEnd, s[1]);
            } else {
                months += curEnd - curStart;
                curStart = s[0];
                curEnd = s[1];
            }
        }
        months += curEnd - curStart;
        int years = months / 12;
        return years <= 50 ? years : 0;
    }

    private static int month(String name, String number, int fallback) {
        if (number != null) {
            int n = Integer.parseInt(number);
            return n >= 1 && n <= 12 ? n : fallback;
        }
        if (name == null) return fallback;
        String[] names = { "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec" };
        String key = name.toLowerCase().substring(0, 3);
        for (int i = 0; i < names.length; i++) if (names[i].equals(key)) return i + 1;
        return fallback;
    }
}
