package com.smartstaff.dto.response;

/** GET /api/interview/config — see InterviewRoom.jsx's PhoneConfirmModal. */
public record InterviewConfigResponse(boolean twilio_configured, String from_number) {}
