package com.smartstaff.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** How a hand-authored assessment is built and marked (assessments.marking_scheme):
 *  how many questions each section needs, the default marks for each of them, the
 *  total number of questions, the time limit and an optional passing mark for this
 *  paper. Field names are the JSON keys.
 *
 *  Papers saved before the employer could customise them have no total_questions,
 *  duration_minutes or passing_percent, and no DEBUG section. For those the total is
 *  the sum of the section counts, the time limit comes from the question estimates,
 *  the passing mark comes from the global level settings, and a missing section needs
 *  zero questions. target_total is kept for old papers and is no longer enforced. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkingScheme(Map<QuestionType, Section> sections, int target_total,
                            Integer total_questions, Integer duration_minutes, Integer passing_percent) {

    public record Section(int count, int marks_each) {}

    /** The standard template: 25 MCQ x1 + 15 ONE_WORD x1 + 5 SCENARIO x6 + 5 PROJECT x6 = 50 questions, 100 marks. */
    public static final MarkingScheme DEFAULTS = new MarkingScheme(
            sectionMap(new Section(25, 1), new Section(15, 1), new Section(5, 6), new Section(5, 6), new Section(0, 6)),
            100, null, null, null);

    public static Map<QuestionType, Section> sectionMap(Section mcq, Section oneWord, Section scenario,
                                                        Section project, Section debug) {
        Map<QuestionType, Section> s = new LinkedHashMap<>();
        s.put(QuestionType.MCQ, mcq);
        s.put(QuestionType.ONE_WORD, oneWord);
        s.put(QuestionType.SCENARIO, scenario);
        s.put(QuestionType.PROJECT, project);
        s.put(QuestionType.DEBUG, debug);
        return s;
    }

    /** Questions the sections ask for, summed. */
    public int totalQuestions() {
        return sections.values().stream().mapToInt(Section::count).sum();
    }

    /** The question count the author declared, or the section sum for older papers. */
    public int declaredTotal() {
        return total_questions != null ? total_questions : totalQuestions();
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
