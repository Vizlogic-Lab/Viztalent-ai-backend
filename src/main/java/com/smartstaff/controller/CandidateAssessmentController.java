package com.smartstaff.controller;

import com.smartstaff.dto.request.AssessmentAnswerInput;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.request.AttemptEventsRequest;
import com.smartstaff.dto.request.CandidateRunRequest;
import com.smartstaff.dto.response.AnswerSavedResponse;
import com.smartstaff.dto.response.AssessmentRunResponse;
import com.smartstaff.dto.response.AttemptEventsResponse;
import com.smartstaff.dto.response.CandidateAssessmentResponse;
import com.smartstaff.service.CandidateAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public candidate API (F7). The single-use invite token in the path is the
 *  only credential — permitAll in SecurityConfig, rate-limited per token
 *  bucket (Run has its own tighter bucket), and masked in request logs. */
@RestController
@RequestMapping("/api/candidate/assessment/{token}")
public class CandidateAssessmentController {

    private final CandidateAssessmentService service;

    public CandidateAssessmentController(CandidateAssessmentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<CandidateAssessmentResponse> start(@PathVariable String token) {
        return ResponseEntity.ok(service.start(token));
    }

    @PutMapping("/answers/{questionId}")
    public ResponseEntity<AnswerSavedResponse> saveAnswer(@PathVariable String token, @PathVariable UUID questionId,
                                                          @RequestBody AssessmentAnswerInput input) {
        return ResponseEntity.ok(service.saveAnswer(token, questionId, input));
    }

    @PostMapping("/run/{questionId}")
    public ResponseEntity<AssessmentRunResponse> run(@PathVariable String token, @PathVariable UUID questionId,
                                                     @Valid @RequestBody CandidateRunRequest req) {
        return ResponseEntity.ok(service.run(token, questionId, req.language(), req.code()));
    }

    /** Body is optional: answers sent here are saved first (if still in time). */
    @PostMapping("/submit")
    public ResponseEntity<CandidateAssessmentResponse> submit(@PathVariable String token,
                                                              @RequestBody(required = false) AssessmentSaveRequest req) {
        return ResponseEntity.ok(service.submit(token, req));
    }

    /** The proctoring checks for this test (consent screen). Doesn't start the clock. */
    @GetMapping("/proctoring")
    public ResponseEntity<com.smartstaff.proctoring.CandidateProctoringView> proctoring(@PathVariable String token) {
        return ResponseEntity.ok(service.proctoring(token));
    }

    @PostMapping("/events")
    public ResponseEntity<AttemptEventsResponse> events(@PathVariable String token,
                                                        @RequestBody AttemptEventsRequest req) {
        return ResponseEntity.ok(service.recordEvents(token, req));
    }
}
