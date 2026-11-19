package com.smartstaff.dto.response;

import java.util.Map;
import java.util.UUID;

/** One question in the company's question book. content is the question as the paper editor
 *  sends it (options, answer, key points, rubric, ...), so the picker can show the answer too. */
public record QuestionBookResponse(
        UUID id,
        String type,
        String skill,
        String difficulty,
        int marks,
        String prompt,
        String origin,
        int usage_count,
        Map<String, Object> content
) {}
