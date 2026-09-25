package com.smartstaff.dto.response;

import java.util.List;

/** GET /api/interview/call_status/{interviewId} — polled every 3s by
 *  InterviewRoom.jsx's PhoneCallRoom. `status` is Twilio's own call-status
 *  string (queued/ringing/in-progress/completed/busy/no-answer/failed/
 *  canceled) where available, else the app-level PENDING/COMPLETED/FAILED
 *  before any Twilio callback has landed yet. `ended` is what stops the poll. */
public record CallStatusResponse(
        String status,
        List<TranscriptTurnResponse> transcript,
        int answered,
        int total,
        boolean ended
) {}
