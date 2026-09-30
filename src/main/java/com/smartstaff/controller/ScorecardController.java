package com.smartstaff.controller;

import com.smartstaff.service.AssessmentService;
import com.smartstaff.service.ScorecardPdfService.ScorecardPdf;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** GET /api/scorecard/{jobId}/{attemptId} — the scored scorecard as a PDF.
 *  Opened as a plain link (no bearer header), so the frontend first calls
 *  POST /api/downloads/sign to get a signed, short-lived URL; the
 *  DownloadSignatureFilter authenticates that signature as the signing user,
 *  and the job-access check below is defence in depth. */
@RestController
public class ScorecardController {

    private final AssessmentService assessmentService;

    public ScorecardController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @GetMapping("/api/scorecard/{jobId}/{attemptId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<byte[]> scorecardPdf(@PathVariable UUID jobId, @PathVariable UUID attemptId) {
        ScorecardPdf pdf = assessmentService.scorecardPdf(jobId, attemptId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(pdf.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }
}
