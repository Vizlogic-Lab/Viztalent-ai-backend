package com.smartstaff.service;

import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;

import java.util.UUID;

public interface ScreeningService {

    RunScreeningResponse runScreening(UUID jobId);

    /** Re-runs only the AI (meaning-based) match for the job's candidates, in the background. Returns the count. */
    int requestAiMatch(UUID jobId);

    ProgressResponse progress();
}
