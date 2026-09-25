package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** See VoiceScreening.jsx's generateAssessment / Candidates.jsx's
 *  EmailPreviewModal.generateAssessment: status ("success"|"error"),
 *  message on error, assessment_url (L1 base link), assessment_urls
 *  (per-level), counts (per-level question counts). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentGenerateResponse(
        String status,
        String message,
        String assessment_url,
        Map<String, String> assessment_urls,
        Map<String, Integer> counts
) {}
