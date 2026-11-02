package com.smartstaff.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** How a hand-authored assessment is built and marked (assessments.marking_scheme):
 *  how many questions each section needs, the default marks for each of them, and
 *  the total the question marks must add up to before the assessment can be published.
 *  Field names are the JSON keys. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkingScheme(Map<QuestionType, Section> sections, int target_total) {

    public record Section(int count, int marks_each) {}

    /** 25 MCQ x1 + 15 ONE_WORD x1 + 5 SCENARIO x6 + 5 PROJECT x6 = 50 questions, 100 marks. */
    public static final MarkingScheme DEFAULTS = new MarkingScheme(sections(1, 1, 6, 6), 100);

    public static Map<QuestionType, Section> sections(int mcq, int oneWord, int scenario, int project) {
        Map<QuestionType, Section> s = new LinkedHashMap<>();
        s.put(QuestionType.MCQ, new Section(25, mcq));
        s.put(QuestionType.ONE_WORD, new Section(15, oneWord));
        s.put(QuestionType.SCENARIO, new Section(5, scenario));
        s.put(QuestionType.PROJECT, new Section(5, project));
        return s;
    }

    public int totalQuestions() {
        return sections.values().stream().mapToInt(Section::count).sum();
    }

    /** Marks when every question keeps its section's default. */
    public int defaultTotal() {
        return sections.values().stream().mapToInt(s -> s.count() * s.marks_each()).sum();
    }

    public int required(QuestionType type) {
        Section s = sections.get(type);
        return s == null ? 0 : s.count();
    }

    public int marksFor(QuestionType type) {
        Section s = sections.get(type);
        return s == null ? 0 : s.marks_each();
    }
}
