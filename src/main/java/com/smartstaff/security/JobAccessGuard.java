package com.smartstaff.security;

import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.JobRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Per-job authorization: admins can act on any job, employees only on jobs
 *  they uploaded (jobs.owner_id) — the same rule GET /api/jobs already applies
 *  when *listing*. Without this, every other job-scoped endpoint (job detail,
 *  screening, assessments, invites, interviews, activity) was reachable by any
 *  logged-in employee who had someone else's job id.
 *
 *  Used from controllers via SpEL, e.g.
 *  {@code @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")}
 *  — a denial becomes the usual 403 from GlobalExceptionHandler.
 *
 *  Deliberately lenient about things that don't exist: an unknown job id, an
 *  unknown interview id, or a non-UUID session id (the frontend's legacy
 *  "local_react_user" placeholder) are all *allowed through*, because there
 *  is no owner to protect and the service layer already answers those with
 *  its own 400/404 — this guard's only job is "does this exist AND belong to
 *  someone else". */
@Component("jobAccess")
public class JobAccessGuard {

    private final JobRepository jobRepository;
    private final InterviewRepository interviewRepository;

    public JobAccessGuard(JobRepository jobRepository, InterviewRepository interviewRepository) {
        this.jobRepository = jobRepository;
        this.interviewRepository = interviewRepository;
    }

    public boolean canAccessJob(UUID jobId, Authentication auth) {
        return allowed(jobId == null ? Optional.empty() : jobRepository.findOwnerIdById(jobId), auth);
    }

    /** For request bodies that carry the job as a `session_id` string. */
    public boolean canAccessSession(String sessionId, Authentication auth) {
        UUID jobId = parseOrNull(sessionId);
        return jobId == null || canAccessJob(jobId, auth);
    }

    public boolean canAccessInterview(UUID interviewId, Authentication auth) {
        return allowed(interviewId == null ? Optional.empty() : interviewRepository.findJobOwnerIdById(interviewId), auth);
    }

    /** For request bodies that carry the interview as an `interview_id` string. */
    public boolean canAccessInterviewId(String interviewId, Authentication auth) {
        UUID id = parseOrNull(interviewId);
        return id == null || canAccessInterview(id, auth);
    }

    private static boolean allowed(Optional<String> ownerId, Authentication auth) {
        if (ownerId.isEmpty()) return true;
        if (auth == null || !(auth.getPrincipal() instanceof User user)) return false;
        return user.getRole() == Role.ADMIN || ownerId.get().equalsIgnoreCase(user.publicId());
    }

    private static UUID parseOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
