package com.smartstaff.dto.response;

import java.time.Instant;
import java.util.List;

/** One row of GET /api/interview/transcripts/{jobId} — see
 *  Candidates.jsx's InterviewTranscripts table and InterviewTranscriptModal
 *  (iv.interview_id, candidate_name, phone, role_title, transcript, saved_at). */
public record InterviewTranscriptResponse(
        String interview_id,
        String candidate_name,
        String phone,
        String role_title,
        List<TranscriptTurnResponse> transcript,
        Instant saved_at
) {}
