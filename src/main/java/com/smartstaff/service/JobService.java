package com.smartstaff.service;

import com.smartstaff.dto.request.JdSkillsOnlyRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface JobService {

    JdUploadResponse uploadJd(MultipartFile file, boolean force, User owner);

    JdUploadResponse uploadJdSkillsOnly(JdSkillsOnlyRequest request, User owner);

    ResumeUploadResponse uploadResumes(UUID jobId, List<MultipartFile> files);

    List<JobSummaryResponse> listJobs(User requester);

    JobDetailResponse getJobDetail(UUID jobId);

    void deleteJob(UUID jobId, User requester);

    List<ResumeSummaryResponse> listResumes(UUID jobId);

    StoredFile loadJdFile(UUID jobId);

    StoredFile loadResumeFile(UUID jobId, String filename);

    /** A file's bytes + the name/content-type to serve it under. */
    record StoredFile(byte[] bytes, String filename, String contentType) {}
}
