package com.smartstaff.controller;

import com.smartstaff.entity.User;
import com.smartstaff.service.impl.AssessmentPromptService;
import com.smartstaff.service.impl.AssessmentPromptService.PromptRequest;
import com.smartstaff.service.impl.AssessmentPromptService.PromptView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Settings → Assessment prompt: read, save, or reset to the built-in default. */
@RestController
@RequestMapping("/api/config/assessment_prompt")
@PreAuthorize("hasRole('ADMIN')")
public class AssessmentPromptController {

    private final AssessmentPromptService prompts;

    public AssessmentPromptController(AssessmentPromptService prompts) {
        this.prompts = prompts;
    }

    @GetMapping
    public ResponseEntity<PromptView> get() {
        return ResponseEntity.ok(prompts.view());
    }

    @PutMapping
    public ResponseEntity<PromptView> save(@RequestBody PromptRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(prompts.save(req, admin));
    }

    @DeleteMapping
    public ResponseEntity<PromptView> reset() {
        return ResponseEntity.ok(prompts.reset());
    }
}
