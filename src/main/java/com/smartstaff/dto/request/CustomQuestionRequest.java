package com.smartstaff.dto.request;

import com.smartstaff.dto.validation.ValidCustomQuestion;
import com.smartstaff.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** One hand-written question. Which fields are required depends on type —
 *  see {@link ValidCustomQuestion}:
 *    MCQ       options (2-6) + correct_index
 *    ONE_WORD  answer (+ accepted_answers: synonyms, other spellings)
 *    SCENARIO  model_answer + key_points (4-6)
 *    PROJECT   rubric (1-8 weighted criteria); model_answer optional
 *  marks: omit to use the section's default from the marking scheme. */
@ValidCustomQuestion
public record CustomQuestionRequest(
        @NotNull QuestionType type,
        @NotBlank @Size(max = 6000) String prompt,
        @Size(max = 6) List<@NotBlank @Size(max = 500) String> options,
        @Min(0) @Max(5) Integer correct_index,
        @Size(max = 100) String answer,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> accepted_answers,
        @Size(max = 6000) String model_answer,
        @Size(max = 6) List<@NotBlank @Size(max = 500) String> key_points,
        @Size(max = 8) List<@Valid RubricItem> rubric,
        @Min(0) @Max(100) Integer marks,
        @Size(max = 120) String skill,
        @Pattern(regexp = "(?i)easy|medium|hard") String difficulty
) {
    public record RubricItem(
            @NotBlank @Size(max = 80) String criterion,
            @Min(1) @Max(10) int weight,
            @NotBlank @Size(max = 500) String description
    ) {}
}
