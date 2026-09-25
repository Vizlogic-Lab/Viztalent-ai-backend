package com.smartstaff.dto.response;

import java.time.Instant;

public record QuestionUploadSummaryResponse(String id, String filename, int count, Instant uploaded_at) {}
