package com.smartstaff.service;

import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;

import java.util.UUID;

public interface ScreeningService {

    RunScreeningResponse runScreening(UUID jobId);

    /** Re-runs only the AI (meaning-based) match for the job's candidates, in the background. Returns the count. */
    int requestAiMatch(UUID jobId);

    ProgressResponse progress();

    /** Recruiter override: pass (or undo the pass of) one candidate. Passing needs a reason. */
    com.smartstaff.dto.response.CandidateRowResponse overrideCandidate(
            UUID jobId, String fileName, boolean passed, String reason, com.smartstaff.entity.User by);
}
