package com.smartstaff.service.impl;

import com.smartstaff.dto.request.InviteMintRequest;
import com.smartstaff.dto.response.InviteMintResponse;
import com.smartstaff.dto.response.InviteResponse;
import com.smartstaff.entity.Assessment;
import com.smartstaff.entity.AssessmentStatus;
import com.smartstaff.entity.Invite;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.AssessmentRepository;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.InviteRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.InviteService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.InviteLinks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mints single-use, expiring candidate assessment links. Only the SHA-256
 *  hash of each raw token is persisted (see {@link Invite} /
 *  V6__assessments.sql), per SMARTSTAFF_BACKEND_DESIGN.md's "store invite
 *  token hashes, not raw tokens" requirement.
 *
 *  Deliberate deviation: Candidates.jsx's comments describe the original
 *  app "coalescing" tokens (same email+level returning an identical token
 *  across repeated mint calls). That's impossible here without keeping the
 *  raw token somewhere retrievable, which would defeat the hash-only
 *  storage the design doc asks for — so every call mints a fresh token
 *  instead. The frontend tolerates this fine: it just uses whatever the
 *  latest mint response returns, and older unused invites simply expire
 *  untouched. See docs/FEATURES.md. */
@Service
public class InviteServiceImpl implements InviteService {

    private final EmailService emailService;
    private final ActivityRecorder activity;
    private final JobRepository jobRepository;
    private final InviteRepository inviteRepository;
    private final SettingsService settingsService;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final CandidateRepository candidateRepository;
    private final ReviewRouter reviewRouter;

    public InviteServiceImpl(JobRepository jobRepository, InviteRepository inviteRepository, SettingsService settingsService,
                             AssessmentRepository assessmentRepository,
                             AssessmentQuestionRepository assessmentQuestionRepository,
                             CandidateRepository candidateRepository, ReviewRouter reviewRouter,
            ActivityRecorder activity, EmailService emailService) {
        this.emailService = emailService;
        this.activity = activity;
        this.reviewRouter = reviewRouter;
        this.jobRepository = jobRepository;
        this.inviteRepository = inviteRepository;
        this.settingsService = settingsService;
        this.assessmentRepository = assessmentRepository;
        this.assessmentQuestionRepository = assessmentQuestionRepository;
        this.candidateRepository = candidateRepository;
    }

    @Override
    @Transactional
    public InviteMintResponse mint(InviteMintRequest req, User admin) {
        UUID jobId = parseSessionId(req.session_id());
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        List<String> levels = req.levels();
        if (levels == null || levels.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one level is required.");
        }
        if (req.candidate_email() == null || req.candidate_email().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "candidate_email is required.");
        }

        // Invites always point at the current READY version.
        Assessment assessment = assessmentRepository.findByJobIdAndCurrentTrue(jobId)
                .filter(a -> a.getStatus() == AssessmentStatus.READY)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "There is no ready assessment for this job yet — generate one first.", "no_ready_assessment"));
        reviewRouter.requireApproved(assessment);
        for (String level : levels) {
            if (assessmentQuestionRepository.countByAssessmentIdAndLevel(assessment.getId(), level) == 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "The current assessment has no " + level + " questions.", "level_not_in_assessment");
            }
        }

        long ttlSeconds = settingsService.getInviteTtlSeconds();
        List<InviteResponse> out = new ArrayList<>();

        if (req.combined()) {
            out.add(mintOne(job, assessment, req, levels, true, ttlSeconds, admin));
        } else {
            for (String level : levels) {
                out.add(mintOne(job, assessment, req, List.of(level), false, ttlSeconds, admin));
            }
        }

        return new InviteMintResponse(true, out);
    }

    private InviteResponse mintOne(Job job, Assessment assessment, InviteMintRequest req, List<String> levels, boolean combined,
                                    long ttlSeconds, User admin) {
        String rawToken = InviteLinks.newToken();

        Invite invite = new Invite();
        invite.setTokenHash(InviteLinks.hash(rawToken));
        invite.setKind("ASSESSMENT");
        invite.setJob(job);
        invite.setAssessment(assessment);
        // Link to the screened candidate's resume when the email matches one of the job's candidates.
        candidateRepository.findFirstByJobIdAndEmailIgnoreCase(job.getId(), req.candidate_email().trim())
                .ifPresent(c -> invite.setResumeId(c.getResume().getId()));
        invite.setCandidateEmail(req.candidate_email().trim());
        invite.setCandidateName(req.candidate_name());
        invite.setLevels(levels);
        invite.setCombined(combined);
        invite.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        invite.setCreatedBy(admin == null ? null : admin.publicId());
        inviteRepository.save(invite);
        activity.record(job.getId(), ActivityRecorder.INVITE, "Assessment invite sent to "
                + ActivityRecorder.who(invite.getCandidateName(), invite.getCandidateEmail())
                + " (" + String.join(", ", levels) + ")", invite.getId());

        String url = InviteLinks.assessment(settingsService.getPublicBaseUrl(), job.jdNumberDisplay(), levels, combined, rawToken);
        EmailService.Queued email = Boolean.TRUE.equals(req.send_email())
                ? emailService.queueInvite(invite, job.getTitle(), url, rawToken, admin) : null;
        return new InviteResponse(String.join(",", levels), url, ttlSeconds, email);
    }

    private static UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No job description found for this session — upload a JD first.");
        }
    }
}
