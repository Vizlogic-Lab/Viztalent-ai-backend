package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.*;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.InviteLinks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** One shared link for a screened group of candidates of a job.
 *
 *  The job's single shared assessment is reused (never a per-candidate AI
 *  assessment). The link opens an entry page; a candidate on the roster gives
 *  the email and name from their resume, and when both match they get a normal
 *  per-candidate Invite, so the existing assessment flow runs unchanged. No
 *  email is sent and nothing here calls an AI model. */
@Service
public class ScreeningBatchService {

    static final int DEFAULT_MIN_SCORE = 50;
    static final List<String> ALL_LEVELS = List.of("L1", "L2", "L3");

    public record CreateRequest(Integer min_score, List<String> levels, Boolean force_new) {}

    public record RosterEntry(String candidate_name, String email, int fit_score, boolean included, boolean entered) {}

    public record BatchView(String batch_id, String url, List<String> levels, int min_score, Instant expires_at,
                            List<RosterEntry> roster, List<String> skipped_no_email, boolean reused) {}

    public record EntryInfo(String job_title, List<String> levels, Instant expires_at) {}

    public record EnterResult(String url, String path) {}

    private final JobRepository jobs;
    private final CandidateRepository candidates;
    private final AssessmentRepository assessments;
    private final AssessmentQuestionRepository questions;
    private final ScreeningBatchRepository batches;
    private final BatchCandidateRepository roster;
    private final InviteRepository invites;
    private final SettingsService settings;
    private final ReviewRouter reviewRouter;
    private final ActivityRecorder activity;
    private final TransactionTemplate tx;

    public ScreeningBatchService(JobRepository jobs, CandidateRepository candidates, AssessmentRepository assessments,
                                 AssessmentQuestionRepository questions, ScreeningBatchRepository batches,
                                 BatchCandidateRepository roster, InviteRepository invites, SettingsService settings,
                                 ReviewRouter reviewRouter, ActivityRecorder activity,
                                 PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.candidates = candidates;
        this.assessments = assessments;
        this.questions = questions;
        this.batches = batches;
        this.roster = roster;
        this.invites = invites;
        this.settings = settings;
        this.reviewRouter = reviewRouter;
        this.activity = activity;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── recruiter side ──────────────────────────────────────────────────

    /** The job's open batch for these settings, or a new one built from the job's screened
     *  candidates. Asking again returns the same link instead of making another. */
    public BatchView createOrGet(UUID jobId, CreateRequest req, User admin) {
        int minScore = req == null || req.min_score() == null ? DEFAULT_MIN_SCORE : req.min_score();
        if (minScore < 0 || minScore > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "min_score must be between 0 and 100.");
        }
        boolean forceNew = req != null && Boolean.TRUE.equals(req.force_new());
        List<String> wanted = req == null || req.levels() == null || req.levels().isEmpty()
                ? ALL_LEVELS : req.levels().stream().filter(ALL_LEVELS::contains).distinct().sorted().toList();
        if (wanted.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "levels must be L1, L2 and/or L3.");

        return tx.execute(s -> {
            jobs.lockById(jobId);   // two clicks at once still make one link
            Job job = jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
            Assessment assessment = assessments.findByJobIdAndCurrentTrue(jobId)
                    .filter(a -> a.getStatus() == AssessmentStatus.READY)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "There is no ready assessment for this job yet — generate one first.", "no_ready_assessment"));
            reviewRouter.requireApproved(assessment);
            List<String> levels = wanted.stream()
                    .filter(l -> questions.countByAssessmentIdAndLevel(assessment.getId(), l) > 0).toList();
            if (levels.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "The current assessment has none of the requested levels.", "level_not_in_assessment");
            }

            List<ScreeningBatch> open = batches.findByJobIdAndExpiresAtAfterOrderByCreatedAtDesc(jobId, Instant.now());
            if (!forceNew) {
                for (ScreeningBatch b : open) {
                    if (b.getMinScore() == minScore && b.getLevels().equals(levels)
                            && b.getAssessment().getId().equals(assessment.getId())) {
                        return view(b, List.of(), true);
                    }
                }
            }
            open.forEach(b -> b.setExpiresAt(Instant.now()));   // a new batch replaces the old link

            ScreeningBatch batch = new ScreeningBatch();
            batch.setJob(job);
            batch.setAssessment(assessment);
            batch.setToken(InviteLinks.newToken());
            batch.setLevels(new ArrayList<>(levels));
            batch.setMinScore(minScore);
            batch.setExpiresAt(Instant.now().plusSeconds(settings.getInviteTtlSeconds()));
            batch.setCreatedBy(admin == null ? null : admin.publicId());

            List<String> skipped = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // Highest score first, so a candidate uploaded twice keeps their best resume.
            for (Candidate c : candidates.findByJobIdOrderByFitScoreDesc(jobId)) {
                String email = c.getEmail() == null ? "" : c.getEmail().trim();
                if (email.isEmpty()) {
                    skipped.add(c.getCandidateName());
                    continue;
                }
                if (!seen.add(email.toLowerCase(Locale.ROOT))) continue;
                BatchCandidate bc = new BatchCandidate();
                bc.setBatch(batch);
                bc.setCandidateName(c.getCandidateName());
                bc.setEmail(email);
                bc.setResumeId(c.getResume() == null ? null : c.getResume().getId());
                bc.setFitScore(c.getFitScore());
                bc.setIncluded(c.getFitScore() >= minScore);
                batch.getCandidates().add(bc);
            }
            if (batch.getCandidates().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "There are no screened candidates with an email for this job — upload resumes and run screening first.",
                        "no_candidates");
            }
            batches.save(batch);
            activity.record(jobId, ActivityRecorder.INVITE, "Shared assessment link created for "
                    + batch.getCandidates().stream().filter(BatchCandidate::isIncluded).count() + " candidates", batch.getId());
            return view(batch, skipped, false);
        });
    }

    /** The job's current open batch, or null. */
    public BatchView current(UUID jobId) {
        return tx.execute(s -> batches.findByJobIdAndExpiresAtAfterOrderByCreatedAtDesc(jobId, Instant.now()).stream()
                .findFirst().map(b -> view(b, List.of(), true)).orElse(null));
    }

    private BatchView view(ScreeningBatch b, List<String> skipped, boolean reused) {
        List<RosterEntry> entries = b.getCandidates().stream()
                .sorted(Comparator.comparingInt(BatchCandidate::getFitScore).reversed())
                .map(c -> new RosterEntry(c.getCandidateName(), c.getEmail(), c.getFitScore(), c.isIncluded(),
                        c.getInviteId() != null))
                .toList();
        String url = settings.getPublicBaseUrl() + "/batch/" + b.getJob().jdNumberDisplay() + "?token=" + b.getToken();
        return new BatchView(b.getId().toString(), url, b.getLevels(), b.getMinScore(), b.getExpiresAt(), entries, skipped, reused);
    }

    // ── candidate side (public; the token is the only credential) ───────

    public EntryInfo info(String token) {
        return tx.execute(s -> {
            ScreeningBatch b = open(token);
            return new EntryInfo(b.getJob().getTitle(), b.getLevels(), b.getExpiresAt());
        });
    }

    /** Returns this candidate's own assessment link when the email AND the name match a roster entry
     *  that cleared the minimum score. The name is compared as a set of words, ignoring case, accents,
     *  punctuation and order ("NAIR, Priya" matches "Priya Nair"). Every refusal is the same, so the
     *  link can't be used to find out who was screened. Coming back replaces the earlier link on the
     *  same invite, so an unfinished attempt carries on where it stopped. */
    public EnterResult enter(String token, String emailRaw, String nameRaw) {
        String email = emailRaw == null ? "" : emailRaw.trim();
        Set<String> name = nameWords(nameRaw);
        return tx.execute(s -> {
            ScreeningBatch b = open(token);
            // Row-locked, so two requests at once can't mint two invites.
            BatchCandidate bc = name.isEmpty() || email.isEmpty() ? null
                    : roster.lockByBatchIdAndEmail(b.getId(), email).filter(BatchCandidate::isIncluded).orElse(null);
            if (bc == null || !name.equals(nameWords(bc.getCandidateName()))) throw new NotOnList();

            Invite invite = bc.getInviteId() == null ? null : invites.findById(bc.getInviteId()).orElse(null);
            String rawToken = InviteLinks.newToken();
            if (invite != null) {
                if (invite.getUsedAt() != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "You've already completed this assessment.", "batch_completed");
                }
                invite.setTokenHash(InviteLinks.hash(rawToken));          // old link stops working
                invite.setExpiresAt(maxOf(invite.getExpiresAt(), b.getExpiresAt()));
            } else {
                invite = new Invite();
                invite.setTokenHash(InviteLinks.hash(rawToken));
                invite.setKind("ASSESSMENT");
                invite.setJob(b.getJob());
                invite.setAssessment(b.getAssessment());
                invite.setResumeId(bc.getResumeId());
                invite.setCandidateEmail(bc.getEmail());
                invite.setCandidateName(bc.getCandidateName());
                invite.setLevels(new ArrayList<>(b.getLevels()));
                invite.setCombined(b.getLevels().size() > 1);
                invite.setExpiresAt(Instant.now().plusSeconds(settings.getInviteTtlSeconds()));
                invite.setCreatedBy("batch-link");
                invites.save(invite);
                bc.setInviteId(invite.getId());
                roster.save(bc);
                activity.record(b.getJob().getId(), ActivityRecorder.INVITE, "Batch link opened by "
                        + ActivityRecorder.who(bc.getCandidateName(), bc.getEmail()), invite.getId());
            }
            String url = InviteLinks.assessment(settings.getPublicBaseUrl(), b.getJob().jdNumberDisplay(), b.getLevels(),
                    invite.isCombined(), rawToken);
            return new EnterResult(url, url.substring(settings.getPublicBaseUrl().length()));
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Same answer for "not on the list", "below the minimum" and "name doesn't match". */
    static final class NotOnList extends ApiException {
        NotOnList() {
            super(HttpStatus.BAD_REQUEST, "We couldn't find you on the list for this assessment. Use the email and full "
                    + "name exactly as they appear on your resume, or contact the recruiter.", "not_on_list");
        }
    }

    /** Lower-case words of a name, accents and punctuation removed. */
    static Set<String> nameWords(String name) {
        if (name == null) return Set.of();
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return plain.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(plain.split(" ")));
    }

    private ScreeningBatch open(String token) {
        ScreeningBatch b = token == null || token.isBlank() ? null : batches.findByToken(token.trim()).orElse(null);
        if (b == null) throw new ApiException(HttpStatus.NOT_FOUND, "This link isn't valid.", "batch_invalid");
        if (b.isExpired()) throw new ApiException(HttpStatus.GONE, "This link has expired. Please ask the recruiter for a new one.",
                "batch_expired");
        return b;
    }

    private static Instant maxOf(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
