package com.smartstaff.controller;

import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.service.impl.PersonalAssessmentGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** HR: write a fresh personal assessment for one screened candidate (the next
 *  invite to that candidate uses it). Invites generate one automatically, so this
 *  is only for replacing a set HR isn't happy with. */
@RestController
public class PersonalAssessmentController {

    public record RegenerateRequest(String session_id, String candidate_email) {}

    private final PersonalAssessmentGenerator generator;
    private final CandidateRepository candidates;

    public PersonalAssessmentController(PersonalAssessmentGenerator generator, CandidateRepository candidates) {
        this.generator = generator;
        this.candidates = candidates;
    }

    @PostMapping("/api/assessment/personal/regenerate")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<Map<String, Object>> regenerate(@RequestBody RegenerateRequest req) {
        UUID jobId;
        try {
            jobId = UUID.fromString(req.session_id().trim());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No job description found for this session — upload a JD first.");
        }
        String email = req.candidate_email() == null ? "" : req.candidate_email().trim();
        var candidate = candidates.findFirstByJobIdAndEmailIgnoreCase(jobId, email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No screened candidate with that email on this job."));
        UUID id = generator.regenerate(candidate.getId());
        return ResponseEntity.ok(Map.of("ok", true, "assessment_id", id.toString()));
    }
}
