package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A candidate's saved answer, echoed back so the page can resume. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CandidateAnswerView(
        String question_id,
        List<Integer> selected_indices,
        String language,
        String code,
        String text
) {}
