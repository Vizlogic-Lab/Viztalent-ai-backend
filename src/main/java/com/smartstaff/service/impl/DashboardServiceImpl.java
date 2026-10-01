package com.smartstaff.service.impl;

import com.smartstaff.dto.response.DashboardResponse;
import com.smartstaff.entity.AttemptStatus;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.InviteRepository;
import com.smartstaff.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** F14: assessment funnel for the jobs the requester can see (admin: all,
 *  employee: own), or for one job. */
@Service
public class DashboardServiceImpl {

    private static final String ASSESSMENT = "ASSESSMENT";

    private final JobRepository jobs;
    private final InviteRepository invites;
    private final AssessmentAttemptRepository attempts;

    public DashboardServiceImpl(JobRepository jobs, InviteRepository invites, AssessmentAttemptRepository attempts) {
        this.jobs = jobs;
        this.invites = invites;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(User requester, UUID jobId) {
        List<Job> visible = jobId != null ? jobs.findById(jobId).map(List::of).orElse(List.of())
                : requester.getRole() == Role.ADMIN ? jobs.findAllByOrderByCreatedAtDesc()
                : jobs.findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(requester.publicId());
        if (visible.isEmpty()) return new DashboardResponse(true, 0, 0, 0, 0, 0, List.of());
        Map<UUID, Job> byId = new LinkedHashMap<>();
        visible.forEach(j -> byId.put(j.getId(), j));
        Set<UUID> ids = byId.keySet();

        List<DashboardResponse.JobAverage> averages = new ArrayList<>();
        for (Object[] row : attempts.averageHandsOnByJob(ids)) {
            Job job = byId.get((UUID) row[0]);
            BigDecimal avg = BigDecimal.valueOf(((Number) row[1]).doubleValue()).setScale(2, RoundingMode.HALF_UP);
            averages.add(new DashboardResponse.JobAverage(job.getId().toString(), job.getTitle(), avg, ((Number) row[2]).longValue()));
        }
        averages.sort(Comparator.comparing(DashboardResponse.JobAverage::job_title, String.CASE_INSENSITIVE_ORDER));

        return new DashboardResponse(true,
                invites.countByJobIdInAndKind(ids, ASSESSMENT),
                attempts.countStartedInvites(ids),
                invites.countByJobIdInAndKindAndUsedAtIsNotNull(ids, ASSESSMENT),
                attempts.countByJobIdInAndStatusAndRecommendation(ids, AttemptStatus.VALIDATED, "PROCEED"),
                attempts.countByJobIdInAndStatus(ids, AttemptStatus.AWAITING_REVIEW),
                averages);
    }
}
