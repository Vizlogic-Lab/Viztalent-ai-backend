package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/universal_execute — see VoiceScreening.jsx's recruiter chat.
 *  session_id is deliberately NOT @NotBlank: the frontend falls back to the
 *  literal string "local_react_user" when no job is active yet, and a chat
 *  message sent before any JD is uploaded is a normal conversational case
 *  (answered with a reply, not an HTTP error), not a validation failure. */
public record UniversalExecuteRequest(
        @NotBlank String command,
        String session_id,
        PlatformConfigRequest platform_config
) {}
