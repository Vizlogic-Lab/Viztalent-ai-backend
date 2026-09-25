package com.smartstaff.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

/** POST /api/interview/save — HR-side browser-mode save (InterviewRoom.jsx). */
public record InterviewSaveRequest(
        @NotBlank String session_id,
        @NotBlank String interview_id,
        String candidate_name,
        String phone,
        String file_name,
        String role_title,
        @Valid @NotEmpty List<TranscriptTurnRequest> transcript,
        Instant started_at,
        Instant ended_at
) {}
