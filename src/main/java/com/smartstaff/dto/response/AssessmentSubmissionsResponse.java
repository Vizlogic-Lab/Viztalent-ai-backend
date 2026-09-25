package com.smartstaff.dto.response;

import java.util.List;

/** GET /api/assessment/submissions/{jobId} — real assessment generation and
 *  submissions land in Phase 6. Until then, honestly reporting "zero
 *  submissions" (not a 404) is what unblocks Candidates.jsx's
 *  Promise.all([submissions, status, job]) from failing outright, which was
 *  hiding the real Phase 3 candidate data behind an empty state. */
public record AssessmentSubmissionsResponse(boolean ok, List<Object> submissions, int pass_threshold) {
    public static AssessmentSubmissionsResponse empty() {
        return new AssessmentSubmissionsResponse(true, List.of(), 50);
    }
}
