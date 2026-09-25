package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/interview/place_call — see InterviewRoom.jsx's PhoneCallRoom.
 *  The frontend also resends candidate_name/file_name/role_title/questions/
 *  intro/outro (everything /prepare just gave it) — Jackson silently drops
 *  fields not declared here, which is deliberate: the interview row
 *  `prepare` created already has all of that, and re-reading it from the DB
 *  is more reliable than trusting a client-resent copy of it. */
public record PlaceCallRequest(
        @NotBlank String session_id,
        @NotBlank String interview_id,
        @NotBlank String phone
) {}
