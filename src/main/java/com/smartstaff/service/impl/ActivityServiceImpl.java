package com.smartstaff.service.impl;

import com.smartstaff.dto.response.ActivityEventResponse;
import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Resume;
import com.smartstaff.repository.ActivityEventRepository;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.ResumeRepository;
import com.smartstaff.service.ActivityService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** F14: assessment events (generated/approved, invite sent, submitted,
 *  evaluated, validated) come from activity_events, written by the services.
 *  JD upload, resume upload and screening predate that table and are still
 *  derived from their own rows' timestamps. */
@Service
public class ActivityServiceImpl implements ActivityService {

    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final ActivityEventRepository activityEvents;

    public ActivityServiceImpl(JobRepository jobRepository, ResumeRepository resumeRepository,
                                CandidateRepository candidateRepository, ActivityEventRepository activityEvents) {
        this.activityEvents = activityEvents;
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

        activityEvents.findByJobIdOrderByAtDescIdDesc(jobId, PageRequest.of(0, Math.max(1, limit)))
                .forEach(e -> events.add(new ActivityEventResponse(e.getKind(), e.getMessage(), e.getAt())));

        return events.stream()
                .sorted(Comparator.comparing(ActivityEventResponse::at).reversed())
                .limit(limit)
                .toList();
    }
}
