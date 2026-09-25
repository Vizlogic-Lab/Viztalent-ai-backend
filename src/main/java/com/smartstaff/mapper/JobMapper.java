package com.smartstaff.mapper;

import com.smartstaff.dto.response.CandidateRowResponse;
import com.smartstaff.dto.response.JdStructResponse;
import com.smartstaff.dto.response.JobDetailResponse;
import com.smartstaff.dto.response.JobSummaryResponse;
import com.smartstaff.dto.response.ResumeSummaryResponse;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Resume;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class JobMapper {

    public JobSummaryResponse toSummaryResponse(Job job) {
        // Interviews/submissions counts are always 0 until Phase 6/7 add those
        // tables — the frontend just renders "—" for a zero count, so this is
        // a safe placeholder rather than a broken contract.
        return new JobSummaryResponse(
                job.getId().toString(),
                job.getJdNumber(),
                job.jdNumberDisplay(),
                job.getTitle(),
                job.getTitle(),
                job.getOriginalFilename(),
                job.allSkills(),
                job.allSkills().size(),
                job.getOwnerId(),
                job.getOwnerName(),
                job.getOwnerEmail(),
                job.getOwnerRole(),
                job.getCreatedAt(),
                null,
                0,
                0
        );
    }

    public JobDetailResponse toDetailResponse(Job job, List<CandidateRowResponse> candidates) {
        JdStructResponse struct = new JdStructResponse(
                job.getExperienceMinYears(),
                job.getExperienceMaxYears(),
                List.of(),
                job.getMustHaveSkills(),
                job.getNiceToHaveSkills()
        );
        return new JobDetailResponse(
                true,
                job.getId().toString(),
                job.jdNumberDisplay(),
                job.getTitle(),
                job.getOriginalFilename(),
                job.getCreatedAt(),
                job.getJdText(),
                struct,
                candidates,
                List.of(),
                List.of(),
                Map.of()
        );
    }

    public ResumeSummaryResponse toResumeSummaryResponse(Resume resume) {
        return new ResumeSummaryResponse(
                resume.getFilename(),
                resume.getSizeBytes(),
                resume.getUploadedAt(),
                "/api/resumes/" + resume.getJob().getId() + "/download/" + resume.getFilename()
        );
    }
}
