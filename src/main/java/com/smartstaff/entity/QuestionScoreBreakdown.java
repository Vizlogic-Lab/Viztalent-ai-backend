package com.smartstaff.entity;

/** One line of an AI grade: a CODE_WRITE rubric criterion (awarded out of
 *  weight) or a SCENARIO key point (covered or not, weight 1). Stored as jsonb
 *  in assessment_question_scores.breakdown. */
public record QuestionScoreBreakdown(String label, double awarded, double weight, String note) {}
