package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/interview/invites/mint — see Candidates.jsx's PhoneConfirmModal
 *  "candidate_link" mode. Internally runs the same prep as
 *  InterviewPrepareRequest (mode=SELF) before minting the token, so the
 *  self-service link's questions are ready the instant the candidate opens it. */
public record InterviewInviteMintRequest(
        @NotBlank String session_id,
        @NotBlank String candidate_email,
        String candidate_name,
        String phone,
        String file_name,
        String language
) {}
