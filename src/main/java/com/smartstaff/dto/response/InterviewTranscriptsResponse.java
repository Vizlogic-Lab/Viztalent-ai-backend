package com.smartstaff.dto.response;

import java.util.List;

public record InterviewTranscriptsResponse(boolean ok, List<InterviewTranscriptResponse> interviews) {}
