package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** One entry of the `transcript` array InterviewRoom.jsx / CandidateInterview.jsx
 *  post to save / save_by_token. `answer` is deliberately not @NotBlank — an
 *  unanswered question is still a valid (if incomplete) transcript entry. */
public record TranscriptTurnRequest(String category, String skill, @NotBlank String question, String answer) {}
