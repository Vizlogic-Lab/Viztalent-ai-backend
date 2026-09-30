package com.smartstaff.dto.request;

import jakarta.validation.Valid;

import java.util.List;

/** Autosave (save_by_token) or final submit (submit_by_token) payload. */
public record AssessmentSaveRequest(@Valid List<AssessmentAnswerInput> answers) {}
