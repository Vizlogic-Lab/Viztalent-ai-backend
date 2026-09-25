package com.smartstaff.service;

import com.smartstaff.dto.request.*;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;

public interface SettingsService {

    ConfigResponse getConfig();

    SaveStatusResponse saveGeminiKey(GeminiKeyRequest req, User admin);

    GeminiTestResponse testGeminiKey();

    PublicUrlSaveResponse savePublicUrl(PublicUrlRequest req, User admin);

    InviteTtlSaveResponse saveInviteTtl(InviteTtlRequest req, User admin);

    TwilioSaveResponse saveTwilio(TwilioConfigRequest req, User admin);

    TwilioTestResponse testTwilio();

    CodeRunnerConfigInfo savePistonUrl(PistonUrlRequest req, User admin);

    SimpleResponse reset();

    // ── internal helpers used by AssessmentService / InviteService / InterviewService ──

    /** Decrypted Gemini key, or null if none is configured. */
    String getGeminiApiKeyOrNull();

    /** Public base URL used to build assessment/invite links, e.g.
     *  "http://localhost:8000" (no trailing slash). */
    String getPublicBaseUrl();

    /** Invite time-to-live in seconds (default 48h). */
    long getInviteTtlSeconds();

    /** True once both a Twilio account SID and auth token are saved. */
    boolean isTwilioConfigured();

    /** Twilio "from" caller ID, or null if not configured. */
    String getTwilioFromNumberOrNull();

    /** Plain (unencrypted) Twilio account SID, or null if not configured. */
    String getTwilioAccountSidOrNull();

    /** Decrypted Twilio auth token — used both to call Twilio's REST API and
     *  to validate the X-Twilio-Signature on inbound webhooks. Null if not configured. */
    String getTwilioAuthTokenOrNull();
}
