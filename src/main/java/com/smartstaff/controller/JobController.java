package com.smartstaff.controller;

import com.smartstaff.dto.request.JdSkillsOnlyRequest;
import com.smartstaff.dto.request.RoleProfileRequest;
import com.smartstaff.dto.request.SkillsUpdateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import com.smartstaff.service.JobService;
import com.smartstaff.service.RoleProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
public class JobController {

    private final JobService jobService;
    private final RoleProfileService roleProfileService;

    public JobController(JobService jobService, RoleProfileService roleProfileService) {
        this.jobService = jobService;
        this.roleProfileService = roleProfileService;
    }

    @PostMapping(value = "/api/upload_jd", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JdUploadResponse> uploadJd(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "force", required = false) String force,
            @AuthenticationPrincipal User owner
    ) {
        boolean forceNew = "1".equals(force) || "true".equalsIgnoreCase(force);
        return ResponseEntity.ok(jobService.uploadJd(file, forceNew, owner));
    }

    @PostMapping("/api/upload_jd_skills")
    public ResponseEntity<JdUploadResponse> uploadJdSkills(
            @Valid @RequestBody JdSkillsOnlyRequest req,
            @AuthenticationPrincipal User owner
    ) {
        return ResponseEntity.ok(jobService.uploadJdSkillsOnly(req, owner));
    }

    @PostMapping(value = "/api/upload_resumes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@jobAccess.canAccessJob(#sessionId, authentication)")
    public ResponseEntity<ResumeUploadResponse> uploadResumes(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("session_id") UUID sessionId
    ) {
        return ResponseEntity.ok(jobService.uploadResumes(sessionId, files));
    }

    @GetMapping("/api/jobs")
    public ResponseEntity<JobsListResponse> listJobs(@AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(new JobsListResponse(true, jobService.listJobs(requester)));
    }

    @GetMapping("/api/jobs/{id}")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<JobDetailResponse> getJob(@PathVariable UUID id) {
        return ResponseEntity.ok(jobService.getJobDetail(id));
    }

    @DeleteMapping("/api/jobs/{id}")
    public ResponseEntity<SimpleResponse> deleteJob(@PathVariable UUID id, @AuthenticationPrincipal User requester) {
        jobService.deleteJob(id, requester);
        return ResponseEntity.ok(SimpleResponse.OK);
    }

    @GetMapping("/api/jd/{id}/resumes")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<ResumesListResponse> listResumes(@PathVariable UUID id) {
        return ResponseEntity.ok(new ResumesListResponse(true, jobService.listResumes(id)));
    }

    @GetMapping("/api/jd/{id}/download")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<byte[]> downloadJd(@PathVariable UUID id) {
        return asAttachment(jobService.loadJdFile(id));
    }

    @GetMapping("/api/resumes/{jobId}/download/{fileName}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<byte[]> downloadResume(@PathVariable UUID jobId, @PathVariable String fileName) {
        return asAttachment(jobService.loadResumeFile(jobId, fileName));
    }

    @GetMapping("/api/jobs/{id}/role_profile")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<RoleProfileResponse> getRoleProfile(@PathVariable UUID id) {
        return ResponseEntity.ok(roleProfileService.getRoleProfile(id));
    }

    @PutMapping("/api/jobs/{id}/role_profile")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<RoleProfileResponse> updateRoleProfile(
            @PathVariable UUID id,
            @RequestBody RoleProfileRequest request,
            @AuthenticationPrincipal User editor
    ) {
        return ResponseEntity.ok(roleProfileService.updateRoleProfile(id, request, editor));
    }

    /** Edits must/nice skills and the experience range. Re-screening is not triggered. */
    @PutMapping("/api/jobs/{id}/skills")
    @PreAuthorize("@jobAccess.canAccessJob(#id, authentication)")
    public ResponseEntity<SimpleResponse> updateSkills(
            @PathVariable UUID id,
            @Valid @RequestBody SkillsUpdateRequest request,
            @AuthenticationPrincipal User editor
    ) {
        jobService.updateJobSkills(id, request, editor);
        return ResponseEntity.ok(SimpleResponse.OK);
    }

    private ResponseEntity<byte[]> asAttachment(JobService.StoredFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
}
