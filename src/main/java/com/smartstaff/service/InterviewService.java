package com.smartstaff.service;

import com.smartstaff.dto.request.InterviewInviteMintRequest;
import com.smartstaff.dto.request.InterviewPrepareRequest;
import com.smartstaff.dto.request.InterviewSaveByTokenRequest;
import com.smartstaff.dto.request.InterviewSaveRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;

import java.util.UUID;

public interface InterviewService {

    InterviewConfigResponse config();

    /** HR-direct prep (browser-mode session today; also the first step of a
     *  Phase 8 phone call). Throws ApiException on failure (no Gemini key,
     *  Gemini error, bad session_id) — InterviewRoom.jsx's prepare useEffect
     *  only has an HTTP-error catch path, unlike the invite-mint flow below. */
    InterviewPrepareResponse prepare(InterviewPrepareRequest req);

    /** Self-service link: runs the same prep internally (mode=SELF), then
     *  mints a single-use token pointing at it. Unlike prepare() above, a
     *  prep failure here comes back as a normal {status:"error", message}
     *  response, not an HTTP error — see InterviewMintResponse's javadoc. */
    InterviewMintResponse mintInvite(InterviewInviteMintRequest req, User admin);

    /** HR-side save of a completed browser-mode interview. */
    SimpleResponse save(InterviewSaveRequest req);

    /** Public — GET /api/interview/by_token/{token}. */
    InterviewPrepareResponse byToken(String token);

    /** Public — POST /api/interview/save_by_token/{token}. Atomically
     *  consumes the token; throws ApiException if it's already used, expired,
     *  or unknown. */
    SimpleResponse saveByToken(String token, InterviewSaveByTokenRequest req);

    InterviewTranscriptsResponse transcripts(UUID jobId);
}
