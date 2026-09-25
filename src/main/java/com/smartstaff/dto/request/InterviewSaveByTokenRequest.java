package com.smartstaff.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

/** POST /api/interview/save_by_token/{token} — public, candidate-facing
 *  (CandidateInterview.jsx). The token identifies the interview; no other
 *  identifying fields are sent or needed. */
public record InterviewSaveByTokenRequest(
        @Valid @NotEmpty List<TranscriptTurnRequest> transcript,
        Instant started_at,
        Instant ended_at
) {}
