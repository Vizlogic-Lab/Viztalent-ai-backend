package com.smartstaff.dto.response;

/** GET /api/config — see Settings.jsx's aiConfig state shape exactly. */
public record ConfigResponse(
        boolean ok,
        GeminiConfigInfo gemini,
        PublicUrlConfigInfo public_url,
        TwilioConfigInfo twilio,
        CodeRunnerConfigInfo code_runner,
        InvitesConfigInfo invites
) {}
