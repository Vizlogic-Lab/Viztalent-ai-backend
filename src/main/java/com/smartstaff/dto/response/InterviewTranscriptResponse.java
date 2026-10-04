package com.smartstaff.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One row of GET /api/interview/transcripts/{jobId} — see
 *  Candidates.jsx's InterviewTranscripts table and InterviewTranscriptModal
 *  (iv.interview_id, candidate_name, phone, role_title, transcript, saved_at).
 *  F16 adds the graded result: evaluation_status (EVALUATING / EVALUATED /
 *  AWAITING_REVIEW / VALIDATED, null = not graded), result_status (PENDING /
 *  PROVISIONAL / VALIDATED), Theory / Hands-on scores and bands, the gate,
 *  flags and the SME review's status and comment. */
public record InterviewTranscriptResponse(
        String interview_id,
        String candidate_name,
        String phone,
        String role_title,
        List<TranscriptTurnResponse> transcript,
        Instant saved_at,
        String mode,
        String evaluation_status,
        String result_status,
        BigDecimal theory_score,
        BigDecimal hands_on_score,
        BigDecimal overall_score,
        String theory_band,
        String hands_on_band,
        String recommendation,
        List<String> flags,
        String validation_reason,
        String review_id,
        String review_status,
        String reviewer_name,
        String sme_comment
) {}
