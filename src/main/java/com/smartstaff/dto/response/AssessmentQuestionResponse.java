package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** See Candidates.jsx's AnswerKeyQuestion: q.type/question/options/
 *  correct_index/correct_indices/skill/difficulty. Field is "question", not
 *  "prompt" — kept verbatim to match. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentQuestionResponse(
        String type,
        String question,
        List<String> options,
        Integer correct_index,
        List<Integer> correct_indices,
        String skill,
        String difficulty
) {}
