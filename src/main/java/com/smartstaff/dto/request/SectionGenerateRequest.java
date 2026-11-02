package com.smartstaff.dto.request;

import com.smartstaff.entity.QuestionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Fills one section with AI questions. count: how many to add (default: whatever
 *  the section still needs). topic_hint steers the AI, e.g. "Oracle SQL tuning". */
public record SectionGenerateRequest(
        @NotNull QuestionType type,
        @Min(1) @Max(25) Integer count,
        @Size(max = 300) String topic_hint,
        @Pattern(regexp = "(?i)easy|medium|hard") String difficulty
) {}
