package com.smartstaff.dto.response;

import java.util.List;

public record JobsListResponse(boolean ok, List<JobSummaryResponse> jobs) {}
