package com.smartstaff.controller;

import com.smartstaff.dto.response.ActivityResponse;
import com.smartstaff.service.ActivityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/api/activity/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<ActivityResponse> activity(
            @PathVariable UUID jobId,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return ResponseEntity.ok(new ActivityResponse(true, activityService.recentActivity(jobId, limit)));
    }
}
