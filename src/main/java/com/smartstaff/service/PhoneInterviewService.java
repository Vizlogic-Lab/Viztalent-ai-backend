package com.smartstaff.service;

import com.smartstaff.dto.request.PlaceCallRequest;
import com.smartstaff.dto.response.CallStatusResponse;
import com.smartstaff.dto.response.PlaceCallResponse;

import java.util.Map;
import java.util.UUID;

/** Outbound Twilio phone interviews (SMARTSTAFF_BACKEND_DESIGN.md §4.5,
 *  Phase 8). See PhoneInterviewServiceImpl's class javadoc for the call
 *  flow and docs/FEATURES.md for what could and couldn't be verified
 *  without a real Twilio account + public tunnel. */
public interface PhoneInterviewService {

    PlaceCallResponse placeCall(PlaceCallRequest req);

    CallStatusResponse callStatus(UUID interviewId);

    /** TwiML (XML) entry point Twilio requests once the callee answers. */
    String voiceWebhook(UUID interviewId);

    /** TwiML (XML) Twilio requests after each Gather completes for
     *  question `seq` — saves that answer, then either asks the next
     *  question or says the outro and hangs up. */
    String answerWebhook(UUID interviewId, int seq, Map<String, String> params);

    /** Twilio's call-status callback — no response body needed (Twilio
     *  ignores it), just updates the Interview row. */
    void statusCallback(Map<String, String> params);
}
