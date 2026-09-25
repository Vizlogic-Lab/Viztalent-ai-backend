package com.smartstaff.dto.response;

/** See VoiceScreening.jsx's handleResumeUpload: status/suggested_reply/has_jd. */
public record ResumeUploadResponse(String status, String suggested_reply, boolean has_jd) {}
