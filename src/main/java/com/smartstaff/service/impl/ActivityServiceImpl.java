package com.smartstaff.service.impl;

import com.smartstaff.dto.response.ActivityEventResponse;
import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Resume;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.ResumeRepository;
import com.smartstaff.service.ActivityService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ActivityServiceImpl implements ActivityService {

    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;

    public ActivityServiceImpl(JobRepository jobRepository, ResumeRepository resumeRepository,
                                CandidateRepository candidateRepository) {
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
    }

    @Override
    public List<ActivityEventResponse> recentActivity(UUID jobId, int limit) {
        List<ActivityEventResponse> events = new ArrayList<>();

        jobRepository.findById(jobId).ifPresent(job ->
                events.add(new ActivityEventResponse("jd", "JD uploaded: " + job.getTitle(), job.getCreatedAt())));

        for (Resume r : resumeRepository.findByJobIdOrderByUploadedAtDesc(jobId)) {
            events.add(new ActivityEventResponse("resumes", "Resume uploaded: " + r.getFilename(), r.getUploadedAt()));
        }

        for (Candidate c : candidateRepository.findByJobIdOrderByFitScoreDesc(jobId)) {
            events.add(new ActivityEventResponse("screening",
                    "Screened " + c.getCandidateName() + " — fit " + c.getFitScore() + "/100", c.getCreatedAt()));
        }

        return events.stream()
                .sorted(Comparator.comparing(ActivityEventResponse::at).reversed())
                .limit(limit)
                .toList();
    }
}
