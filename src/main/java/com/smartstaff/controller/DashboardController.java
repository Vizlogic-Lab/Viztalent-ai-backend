package com.smartstaff.controller;

import com.smartstaff.dto.response.DashboardResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.security.JobAccessGuard;
import com.smartstaff.service.impl.DashboardServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** F14: GET /api/dashboard[?job_id=] — assessment funnel and average hands-on by job. */
@RestController
public class DashboardController {

    private final DashboardServiceImpl dashboard;
    private final JobAccessGuard jobAccess;

    public DashboardController(DashboardServiceImpl dashboard, JobAccessGuard jobAccess) {
        this.dashboard = dashboard;
        this.jobAccess = jobAccess;
    }

    @GetMapping("/api/dashboard")
    public ResponseEntity<DashboardResponse> dashboard(@RequestParam(name = "job_id", required = false) UUID jobId,
                                                       Authentication authentication) {
        if (jobId != null && !jobAccess.canAccessJob(jobId, authentication)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to do that.");
        }
        return ResponseEntity.ok(dashboard.dashboard((User) authentication.getPrincipal(), jobId));
    }
}
