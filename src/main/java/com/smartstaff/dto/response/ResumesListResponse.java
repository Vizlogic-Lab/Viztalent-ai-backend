package com.smartstaff.dto.response;

import java.util.List;

public record ResumesListResponse(boolean ok, List<ResumeSummaryResponse> resumes) {}
