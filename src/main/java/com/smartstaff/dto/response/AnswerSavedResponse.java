package com.smartstaff.dto.response;

import java.time.Instant;

/** PUT /api/candidate/assessment/{token}/answers/{questionId}. */
public record AnswerSavedResponse(boolean ok, String question_id, Instant saved_at) {}
