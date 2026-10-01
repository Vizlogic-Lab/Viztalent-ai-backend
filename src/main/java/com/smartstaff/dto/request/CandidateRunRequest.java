package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/candidate/assessment/{token}/run/{questionId}. */
public record CandidateRunRequest(@NotBlank String language, String code) {}
