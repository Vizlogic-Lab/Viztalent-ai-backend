package com.smartstaff.controller;

import com.smartstaff.entity.User;
import com.smartstaff.service.impl.AnalyticsService;
import com.smartstaff.service.impl.CalibrationSetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** F15: calibration and question-quality analytics (admin only; also locked in SecurityConfig). */
@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasRole('ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final CalibrationSetService calibrationSets;

    public AnalyticsController(AnalyticsService analytics, CalibrationSetService calibrationSets) {
        this.analytics = analytics;
        this.calibrationSets = calibrationSets;
    }

    @GetMapping("/calibration")
    public ResponseEntity<Map<String, Object>> calibration(@RequestParam(name = "job_id", required = false) UUID jobId) {
        return ResponseEntity.ok(analytics.calibration(jobId));
    }

    @GetMapping("/questions")
    public ResponseEntity<Map<String, Object>> questions(@RequestParam(name = "job_id", required = false) UUID jobId,
                                                         @RequestParam(name = "flagged", defaultValue = "false") boolean flagged) {
        return ResponseEntity.ok(analytics.questionStats(jobId, flagged));
    }

    @GetMapping("/bank_items")
    public ResponseEntity<Map<String, Object>> bankItems() {
        return ResponseEntity.ok(analytics.bankItems());
    }

    @PostMapping("/bank_items/refresh")
    public ResponseEntity<Map<String, Object>> refreshBankItems() {
        return ResponseEntity.ok(analytics.refreshBankQuality());
    }

    @PostMapping("/bank_items/{itemId}/enable")
    public ResponseEntity<Map<String, Object>> enableBankItem(@PathVariable UUID itemId, @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(analytics.enableBankItem(itemId, actor));
    }

    @PostMapping("/calibration_set")
    public ResponseEntity<Map<String, Object>> importCalibrationSet(@RequestBody CalibrationSetService.ImportRequest req,
                                                                    @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(calibrationSets.importSet(req, actor));
    }

    @GetMapping("/calibration_set")
    public ResponseEntity<Map<String, Object>> calibrationRuns() {
        return ResponseEntity.ok(calibrationSets.list());
    }

    @GetMapping("/calibration_set/{runId}")
    public ResponseEntity<Map<String, Object>> calibrationRun(@PathVariable UUID runId) {
        return ResponseEntity.ok(calibrationSets.get(runId));
    }

    @PostMapping("/calibration_set/{runId}/rerun")
    public ResponseEntity<Map<String, Object>> rerunCalibrationSet(@PathVariable UUID runId, @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(calibrationSets.rerun(runId, actor));
    }
}
