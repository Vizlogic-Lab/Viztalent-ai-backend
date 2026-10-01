package com.smartstaff.controller;

import com.smartstaff.dto.response.AssessmentSubmissionsResponse;
import com.smartstaff.dto.response.AttemptReviewResponse;
import com.smartstaff.entity.ScoringSettings;
import com.smartstaff.entity.User;
import com.smartstaff.service.ResultsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** F10 — assessment results for HR: per job, across the requester's jobs, one
 *  attempt in full, and the job's scoring weights/gates. */
@RestController
public class ResultsController {

    private final ResultsService results;

    public ResultsController(ResultsService results) {
        this.results = results;
    }

    @GetMapping("/api/assessment/submissions/{jobId}")
    @PreAuthorize("@jobAccess.canReadResults(#jobId, authentication)")
    public ResponseEntity<AssessmentSubmissionsResponse> submissions(@PathVariable UUID jobId) {
        return ResponseEntity.ok(results.submissions(jobId));
    }

    @GetMapping("/api/assessment/submissions_all")
    public ResponseEntity<AssessmentSubmissionsResponse> submissionsAll(@AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(results.submissionsAll(requester));
    }

    @GetMapping("/api/assessment/attempts/{attemptId}")
    @PreAuthorize("@jobAccess.canAccessAttempt(#attemptId, authentication)")
    public ResponseEntity<AttemptReviewResponse> attempt(@PathVariable UUID attemptId) {
        return ResponseEntity.ok(results.attempt(attemptId));
    }

    @GetMapping("/api/assessment/scoring_settings/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<ScoringSettings> scoringSettings(@PathVariable UUID jobId) {
        return ResponseEntity.ok(results.scoringSettings(jobId));
    }

    @PutMapping("/api/assessment/scoring_settings/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<ScoringSettings> updateScoringSettings(@PathVariable UUID jobId,
                                                                 @RequestBody ScoringSettings settings) {
        return ResponseEntity.ok(results.updateScoringSettings(jobId, settings));
    }
}
