package com.smartstaff.controller;

import com.smartstaff.entity.User;
import com.smartstaff.service.impl.ScreeningBatchService;
import com.smartstaff.service.impl.ScreeningBatchService.BatchView;
import com.smartstaff.service.impl.ScreeningBatchService.CreateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Recruiter side of the shared batch link: one link for a screened group of a job's candidates. */
@RestController
public class BatchController {

    private final ScreeningBatchService service;

    public BatchController(ScreeningBatchService service) {
        this.service = service;
    }

    /** The job's open batch (link, roster and scores), or {"batch": null}. */
    @GetMapping("/api/jobs/{jobId}/batch")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> current(@PathVariable UUID jobId) {
        BatchView view = service.current(jobId);
        return ResponseEntity.ok(Map.of("ok", true, "batch", view == null ? Map.of() : view));
    }

    /** Creates the batch from the job's screened candidates, or returns the open one that already matches. */
    @PostMapping("/api/jobs/{jobId}/batch")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID jobId, @RequestBody(required = false) CreateRequest req,
                                                      @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(Map.of("ok", true, "batch", service.createOrGet(jobId, req, admin)));
    }
}
