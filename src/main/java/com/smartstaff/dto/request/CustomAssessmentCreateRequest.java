package com.smartstaff.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Starts a hand-authored draft. marking_scheme is optional; the default is
 *  25 MCQ x1 + 15 ONE_WORD x1 + 5 SCENARIO x6 + 5 PROJECT x6 = 100 marks. */
public record CustomAssessmentCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @Valid MarkingSchemeRequest marking_scheme
) {}
