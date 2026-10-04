package com.smartstaff.controller;

import com.smartstaff.entity.User;
import com.smartstaff.service.impl.InterviewQuestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** F16: the recruiter's own L1 interview questions for a job (job owner or admin). */
@RestController
@RequestMapping("/api/jobs/{jobId}/interview_questions")
@PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
public class InterviewQuestionController {

    private final InterviewQuestionService service;

    public InterviewQuestionController(InterviewQuestionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable UUID jobId) {
        return ResponseEntity.ok(service.list(jobId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID jobId, @RequestBody InterviewQuestionService.QuestionInput req,
                                                      @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(service.create(jobId, req, actor));
    }

    @PutMapping("/order")
    public ResponseEntity<Map<String, Object>> reorder(@PathVariable UUID jobId, @RequestBody InterviewQuestionService.Order req) {
        return ResponseEntity.ok(service.reorder(jobId, req));
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> configure(@PathVariable UUID jobId, @RequestBody InterviewQuestionService.Config req) {
        return ResponseEntity.ok(service.configure(jobId, req));
    }

    @PutMapping("/{questionId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID jobId, @PathVariable UUID questionId,
                                                      @RequestBody InterviewQuestionService.QuestionInput req) {
        return ResponseEntity.ok(service.update(jobId, questionId, req));
    }

    @DeleteMapping("/{questionId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID jobId, @PathVariable UUID questionId) {
        return ResponseEntity.ok(service.delete(jobId, questionId));
    }
}
