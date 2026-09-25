package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/interview/place_call response. See InterviewRoom.jsx's
 *  PhoneCallRoom: success needs status:"ok" + call_sid; failure needs a
 *  non-"ok" status + `detail` — a 200 either way, same "graceful failure"
 *  contract as InterviewMintResponse, not an HTTP error, since placing a
 *  call can fail for very normal reasons (bad number, no Twilio balance). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceCallResponse(String status, String call_sid, String detail) {

    public static PlaceCallResponse ok(String callSid) {
        return new PlaceCallResponse("ok", callSid, null);
    }

    public static PlaceCallResponse error(String detail) {
        return new PlaceCallResponse("error", null, detail);
    }
}
