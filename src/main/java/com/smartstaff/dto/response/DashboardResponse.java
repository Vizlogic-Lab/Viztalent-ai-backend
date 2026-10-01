package com.smartstaff.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** F14: GET /api/dashboard. Counts are per invite (sent / started / completed)
 *  and per level-attempt (passed / awaiting review). passed = validated PROCEED. */
public record DashboardResponse(
        boolean ok,
        long assessments_sent,
        long assessments_started,
        long assessments_completed,
        long passed,
        long awaiting_review,
        List<JobAverage> average_hands_on_by_job
) {
    public record JobAverage(String job_id, String job_title, BigDecimal average_hands_on, long results) {}
}
