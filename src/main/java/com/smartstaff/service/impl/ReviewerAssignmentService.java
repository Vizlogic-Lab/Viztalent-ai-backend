package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.UserMapper;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.JobReviewerRepository;
import com.smartstaff.repository.JobRoleProfileRepository;
import com.smartstaff.repository.UserRepository;
import com.smartstaff.util.ReviewRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** F11: which technical reviewers (SMEs) a job has, its review policy, and suggestions. */
@Service
public class ReviewerAssignmentService {

    public record AssignRequest(List<String> user_ids, ReviewPolicy review_policy) {}

    private final JobRepository jobs;
    private final JobReviewerRepository jobReviewers;
    private final UserRepository users;
    private final JobRoleProfileRepository roleProfiles;
    private final UserMapper userMapper;

    public ReviewerAssignmentService(JobRepository jobs, JobReviewerRepository jobReviewers, UserRepository users,
                                     JobRoleProfileRepository roleProfiles, UserMapper userMapper) {
        this.jobs = jobs;
        this.jobReviewers = jobReviewers;
        this.users = users;
        this.roleProfiles = roleProfiles;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID jobId) {
        Job job = job(jobId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (JobReviewer r : jobReviewers.findByJobIdOrderByAssignedAtAsc(jobId)) {
            users.findById(r.getUserId()).ifPresent(u -> {
                Map<String, Object> m = row(u);
                m.put("assigned_by", r.getAssignedBy());
                m.put("assigned_at", r.getAssignedAt());
                list.add(m);
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("job_id", jobId.toString());
        out.put("reviewers", list);
        out.put("review_policy", ReviewPolicy.orDefaults(job.getReviewPolicy()));
        return out;
    }

    /** Replaces the job's reviewer list (and its policy when given). */
    @Transactional
    public Map<String, Object> assign(UUID jobId, AssignRequest req, User requester) {
        Job job = job(jobId);
        if (req == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to change.");
        if (req.review_policy() != null) {
            String problem = req.review_policy().problem();
            if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem);
            job.setReviewPolicy(req.review_policy());
            jobs.save(job);
        }
        if (req.user_ids() != null) {
            List<User> chosen = new ArrayList<>();
            for (String raw : new LinkedHashSet<>(req.user_ids())) {
                User u = resolve(raw);
                if (u == null || u.getRole() != Role.REVIEWER) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Not a reviewer account: " + raw, "not_a_reviewer");
                }
                chosen.add(u);
            }
            Map<UUID, JobReviewer> existing = new HashMap<>();
            jobReviewers.findByJobIdOrderByAssignedAtAsc(jobId).forEach(r -> existing.put(r.getUserId(), r));
            Set<UUID> keep = new HashSet<>();
            for (User u : chosen) {
                keep.add(u.getId());
                if (!existing.containsKey(u.getId())) {
                    jobReviewers.save(new JobReviewer(jobId, u.getId(), requester == null ? null : requester.publicId()));
                }
            }
            existing.values().stream().filter(r -> !keep.contains(r.getUserId())).forEach(jobReviewers::delete);
        }
        jobReviewers.flush();
        return get(jobId);
    }

    /** Every reviewer, ranked by overlap with the job's role-profile skill weights. */
    @Transactional(readOnly = true)
    public Map<String, Object> suggest(UUID jobId) {
        Job job = job(jobId);
        Map<String, Integer> weights = roleProfiles.findById(jobId)
                .map(JobRoleProfile::getSkillWeights).filter(w -> w != null && !w.isEmpty()).orElse(null);
        if (weights == null) {
            weights = new LinkedHashMap<>();
            for (String s : job.getMustHaveSkills()) weights.put(s, 3);
            for (String s : job.getNiceToHaveSkills()) weights.putIfAbsent(s, 1);
        }
        Map<UUID, User> byId = new LinkedHashMap<>();
        Map<UUID, List<String>> skills = new LinkedHashMap<>();
        for (User u : users.findByRoleOrderByNameAsc(Role.REVIEWER)) {
            if (u.getStatus() != AccountStatus.APPROVED) continue;
            byId.put(u.getId(), u);
            skills.put(u.getId(), u.getReviewerSkills());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewRules.Suggestion s : ReviewRules.rank(skills, weights)) {
            Map<String, Object> m = row(byId.get(s.userId()));
            m.put("score", s.score());
            m.put("matched_skills", s.matched());
            m.put("assigned", jobReviewers.existsByJobIdAndUserId(jobId, s.userId()));
            out.add(m);
        }
        return Map.of("ok", true, "job_id", jobId.toString(), "suggestions", out);
    }

    private Map<String, Object> row(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getId().toString());
        m.put("account", userMapper.toAccountResponse(u));
        return m;
    }

    /** A reviewer by user UUID or by sign-in email. */
    private User resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return users.findById(UUID.fromString(raw.trim())).orElse(null);
        } catch (IllegalArgumentException e) {
            return users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, raw.trim()).orElse(null);
        }
    }

    private Job job(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
    }
}
