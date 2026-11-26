package com.smartstaff.util;

import com.smartstaff.entity.Assessment;
import com.smartstaff.entity.AssessmentQuestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How long one level of an assessment lasts and what is in it. The candidate's timer and the
 *  invite text both use this, so the invite states the real time limit instead of a guess. */
public final class AssessmentTiming {

    /** Used when a level has neither a paper duration nor question time estimates. */
    public static final long DEFAULT_LEVEL_SECONDS = 45L * 60;

    /** One level: its time limit in minutes, how many questions, and how many of each type
     *  (keyed by the question type name, e.g. MCQ, ONE_WORD, SCENARIO). */
    public record LevelInfo(int minutes, int questions, Map<String, Integer> types) {}

    private AssessmentTiming() {}

    /** A custom paper's own duration; otherwise the sum of the level's question time estimates;
     *  otherwise 45 minutes. */
    public static long levelSeconds(Assessment assessment, List<AssessmentQuestion> levelQuestions) {
        Integer paperMinutes = assessment.getMarkingScheme() == null ? null
                : assessment.getMarkingScheme().duration_minutes();
        long budget = paperMinutes != null ? paperMinutes * 60L : levelQuestions.stream()
                .filter(q -> q.getTimeEstimateSec() != null)
                .mapToLong(AssessmentQuestion::getTimeEstimateSec)
                .sum();
        return budget > 0 ? budget : DEFAULT_LEVEL_SECONDS;
    }

    public static LevelInfo levelInfo(Assessment assessment, List<AssessmentQuestion> levelQuestions) {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (AssessmentQuestion q : levelQuestions) {
            if (q.getType() != null) types.merge(q.getType().name(), 1, Integer::sum);
        }
        long seconds = levelSeconds(assessment, levelQuestions);
        int minutes = (int) Math.max(1, Math.round(seconds / 60.0));
        return new LevelInfo(minutes, levelQuestions.size(), types);
    }
}
