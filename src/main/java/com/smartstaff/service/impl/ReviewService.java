package com.smartstaff.service.impl;

import com.smartstaff.dto.request.QuestionEditRequest;
import com.smartstaff.dto.response.AssessmentQuestionResponse;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.AssessmentQuestionService;
import com.smartstaff.util.ResultNarrator;
import com.smartstaff.util.ScoringRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** F12: the SME's side of a review — queue, claim, question-set approval,
 *  result sign-off (with per-question adjustments) and the admin reopen.
 *  Every action writes review_audit. Signed-off results are VALIDATED and locked. */
@Service
public class ReviewService {

    /** criteria (optional, F15): the SME's score per rubric criterion, on the engine's criterion scale. */
    public record Adjustment(String question_id, BigDecimal sme_score, String reason, List<CriterionScore> criteria) {}

    public record CriterionScore(String criterion, BigDecimal sme_score) {}

    public record AttemptDecision(List<Adjustment> adjustments, String theory_band, String hands_on_band,
                                  String recommendation, String comment, String action) {}

    public record QuestionSetDecision(String action, String comment) {}

    public record Reopen(String reason) {}

    static final Set<String> BANDS = Set.of("STRONG", "WORKING", "BASIC", "NOT_DEMONSTRATED");
    static final Set<String> RECOMMENDATIONS = Set.of("PROCEED", "HR_REVIEW", "NOT_RECOMMENDED");
    private static final List<SmeReview.Status> OPEN = List.of(SmeReview.Status.PENDING, SmeReview.Status.IN_REVIEW);

    private final ActivityRecorder activity;
    private final SmeReviewRepository reviews;
    private final ReviewAuditRepository audit;
    private final JobReviewerRepository jobReviewers;
    private final JobRepository jobs;
    private final UserRepository users;
    private final AssessmentRepository assessments;
    private final AssessmentQuestionRepository questions;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentAnswerRepository answers;
    private final AttemptEventRepository events;
    private final JobRoleProfileRepository roleProfiles;
    private final AssessmentQuestionService questionService;
    private final AssessmentMapper mapper;
    private final TransactionTemplate tx;

    public ReviewService(SmeReviewRepository reviews, ReviewAuditRepository audit, JobReviewerRepository jobReviewers,
                         JobRepository jobs, UserRepository users, AssessmentRepository assessments,
                         AssessmentQuestionRepository questions, AssessmentAttemptRepository attempts,
                         AssessmentAnswerRepository answers, AttemptEventRepository events,
                         JobRoleProfileRepository roleProfiles, AssessmentQuestionService questionService,
                         AssessmentMapper mapper, PlatformTransactionManager transactionManager,
            ActivityRecorder activity) {
        this.activity = activity;
        this.reviews = reviews;
        this.audit = audit;
        this.jobReviewers = jobReviewers;
        this.jobs = jobs;
        this.users = users;
        this.assessments = assessments;
        this.questions = questions;
        this.attempts = attempts;
        this.answers = answers;
        this.events = events;
        this.roleProfiles = roleProfiles;
        this.questionService = questionService;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── queue ───────────────────────────────────────────────────────────

    /** Reviewer: open reviews of assigned jobs; admin: all. Oldest due first.
     *  Filters: job_id, subject_type, status (default: PENDING + IN_REVIEW). */
    public Map<String, Object> queue(User actor, UUID jobId, String subjectType, String status) {
        return tx.execute(s -> {
            List<SmeReview.Status> statuses = status == null || status.isBlank() ? OPEN
                    : List.of(parse(SmeReview.Status.class, status, "status"));
            SmeReview.Subject subject = subjectType == null || subjectType.isBlank() ? null
                    : parse(SmeReview.Subject.class, subjectType, "subject_type");
            List<SmeReview> rows;
            if (actor.getRole() == Role.ADMIN) {
                rows = jobId == null ? reviews.findOpen(statuses) : reviews.findOpenForJobs(List.of(jobId), statuses);
            } else {
                List<UUID> mine = jobReviewers.findByUserId(actor.getId()).stream().map(JobReviewer::getJobId).toList();
                if (jobId != null) mine = mine.contains(jobId) ? List.of(jobId) : List.of();
                rows = mine.isEmpty() ? List.of() : reviews.findOpenForJobs(mine, statuses);
            }
            return Map.of("ok", true, "reviews",
                    rows.stream().filter(r -> subject == null || r.getSubjectType() == subject).map(this::row).toList());
        });
    }

    /** Admin: open reviews past their due date. */
    public Map<String, Object> overdue() {
        return tx.execute(s -> Map.of("ok", true, "reviews",
                reviews.findOverdue(OPEN, Instant.now()).stream().map(this::row).toList()));
    }

    /** One review with its audit trail; a question set also carries its questions.
     *  claimed_by_me tells the SME screens whether this user may act on it now. */
    public Map<String, Object> get(UUID reviewId, User actor) {
        return tx.execute(s -> {
            SmeReview r = review(reviewId);
            Map<String, Object> out = new LinkedHashMap<>(row(r));
            out.put("ok", true);
            out.put("claimed_by_me", actor != null && actor.getId().equals(r.getReviewerId()));
            out.put("adjustments", r.getAdjustments());
            out.put("final_theory_band", r.getFinalTheoryBand());
            out.put("final_hands_on_band", r.getFinalHandsOnBand());
            out.put("final_recommendation", r.getFinalRecommendation());
            out.put("comment", r.getComment());
            out.put("signed_off_at", r.getSignedOffAt());
            if (r.getSubjectType() == SmeReview.Subject.QUESTION_SET) {
                out.put("questions", questions.findByAssessmentIdOrderByLevelAscSeqAsc(r.getSubjectId()).stream()
                        .map(mapper::toQuestionResponse).toList());
            }
            out.put("audit", audit.findByReviewIdOrderByIdAsc(r.getId()).stream().map(x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("action", x.getAction());
                m.put("actor_id", x.getActorId() == null ? null : x.getActorId().toString());
                m.put("before", x.getBefore());
                m.put("after", x.getAfter());
                m.put("at", x.getAt());
                return m;
            }).toList());
            return out;
        });
    }

    // ── claim ───────────────────────────────────────────────────────────

    public Map<String, Object> claim(UUID reviewId, User actor) {
        return tx.execute(s -> {
            SmeReview r = lock(reviewId);
            requireOpen(r);
            if (r.getReviewerId() != null && !r.getReviewerId().equals(actor.getId()) && actor.getRole() != Role.ADMIN) {
                throw new ApiException(HttpStatus.CONFLICT, "Another reviewer has already claimed this review.", "already_claimed");
            }
            Map<String, Object> before = state(r);
            r.setReviewerId(actor.getId());
            r.setStatus(SmeReview.Status.IN_REVIEW);
            reviews.save(r);
            log(r, actor, "CLAIMED", before, state(r));
            return row(r);
        });
    }

    // ── question sets ───────────────────────────────────────────────────

    public Map<String, Object> decideQuestionSet(UUID reviewId, QuestionSetDecision req, User actor) {
        return tx.execute(s -> {
            SmeReview r = lock(reviewId);
            requireSubject(r, SmeReview.Subject.QUESTION_SET);
            requireOpen(r);
            requireMine(r, actor);
            String action = req == null || req.action() == null ? "" : req.action().trim().toUpperCase(Locale.ROOT);
            Map<String, Object> before = state(r);
            switch (action) {
                case "APPROVE" -> r.setStatus(SmeReview.Status.APPROVED);
                case "RETURN" -> {
                    if (blank(req.comment())) throw new ApiException(HttpStatus.BAD_REQUEST, "Say what needs changing.");
                    r.setStatus(SmeReview.Status.RETURNED);
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "action must be APPROVE or RETURN.");
            }
            if (r.getReviewerId() == null) r.setReviewerId(actor.getId());
            r.setComment(req.comment());
            r.setSignedOffAt(Instant.now());
            reviews.save(r);
            log(r, actor, action.equals("APPROVE") ? "APPROVED" : "RETURNED", before, state(r));
            int version = assessments.findById(r.getSubjectId()).map(Assessment::getVersion).orElse(0);
            activity.record(r.getJobId(), ActivityRecorder.ASSESSMENT, "Assessment v" + version
                    + (action.equals("APPROVE") ? " approved by " : " returned by ") + actor.getName(), r.getSubjectId());
            return row(r);
        });
    }

    /** F6 edit, from inside the question-set review. */
    public AssessmentQuestionResponse editQuestion(UUID reviewId, UUID questionId, QuestionEditRequest req, User actor) {
        tx.executeWithoutResult(s -> requireEditable(reviewId, questionId, actor));
        AssessmentQuestionResponse out = questionService.edit(questionId, req);
        tx.executeWithoutResult(s -> log(review(reviewId), actor, "QUESTION_EDITED", Map.of("question_id", questionId.toString()), null));
        return out;
    }

    /** F6 regenerate, from inside the question-set review. */
    public AssessmentQuestionResponse regenerateQuestion(UUID reviewId, UUID questionId, User actor) {
        tx.executeWithoutResult(s -> requireEditable(reviewId, questionId, actor));
        AssessmentQuestionResponse out = questionService.regenerate(questionId);
        tx.executeWithoutResult(s -> log(review(reviewId), actor, "QUESTION_REGENERATED",
                Map.of("question_id", questionId.toString()), Map.of("question_id", String.valueOf(out.question_id()))));
        return out;
    }

    private void requireEditable(UUID reviewId, UUID questionId, User actor) {
        SmeReview r = review(reviewId);
        requireSubject(r, SmeReview.Subject.QUESTION_SET);
        requireOpen(r);
        requireMine(r, actor);
        AssessmentQuestion q = questions.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
        if (!q.getAssessment().getId().equals(r.getSubjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That question isn't part of this review.");
        }
    }

    // ── attempt results ─────────────────────────────────────────────────

    public Map<String, Object> decideAttempt(UUID reviewId, AttemptDecision req, User actor) {
        return tx.execute(s -> {
            SmeReview r = lock(reviewId);
            requireSubject(r, SmeReview.Subject.ATTEMPT);
            requireOpen(r);
            requireMine(r, actor);
            if (req == null || req.action() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "action is required.");
            AssessmentAttempt a = attempts.lockById(r.getSubjectId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attempt not found."));
            if (a.getStatus() != AttemptStatus.AWAITING_REVIEW) {
                throw new ApiException(HttpStatus.CONFLICT, "This attempt isn't waiting for review.", "not_awaiting_review");
            }
            Map<String, Object> before = state(r, a);
            String action = req.action().trim().toUpperCase(Locale.ROOT);
            if (r.getReviewerId() == null) r.setReviewerId(actor.getId());
            r.setComment(req.comment());
            r.setSignedOffAt(Instant.now());

            if (action.equals("RETURN_TO_HR")) {
                if (blank(req.comment())) throw new ApiException(HttpStatus.BAD_REQUEST, "Say why it goes back to HR.");
                r.setStatus(SmeReview.Status.RETURNED);
                a.setStatus(AttemptStatus.EVALUATED);   // provisional again, HR decides
                attempts.save(a);
                reviews.save(r);
                log(r, actor, "RETURNED_TO_HR", before, state(r, a));
                return row(r);
            }
            if (!action.equals("SIGN_OFF")) throw new ApiException(HttpStatus.BAD_REQUEST, "action must be SIGN_OFF or RETURN_TO_HR.");

            Map<UUID, AssessmentAnswer> byQuestion = new HashMap<>();
            for (AssessmentAnswer ans : answers.findByAttemptId(a.getId())) byQuestion.put(ans.getQuestion().getId(), ans);
            List<Map<String, Object>> applied = new ArrayList<>();
            Set<UUID> adjusted = new HashSet<>();
            for (Adjustment adj : req.adjustments() == null ? List.<Adjustment>of() : req.adjustments()) {
                UUID qid = uuid(adj.question_id(), "question_id");
                AssessmentAnswer ans = byQuestion.get(qid);
                if (ans == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Question " + qid + " isn't part of this attempt.");
                if (!adjusted.add(qid)) throw new ApiException(HttpStatus.BAD_REQUEST, "Question " + qid + " is adjusted twice.");
                int points = ans.getQuestion().getPoints();
                if (adj.sme_score() == null || adj.sme_score().signum() < 0 || adj.sme_score().doubleValue() > points) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "sme_score for " + qid + " must be between 0 and " + points + ".");
                }
                if (blank(adj.reason())) throw new ApiException(HttpStatus.BAD_REQUEST, "Every adjustment needs a reason.");
                BigDecimal sme = adj.sme_score().setScale(2, RoundingMode.HALF_UP);
                Map<String, BigDecimal> criteria = criteria(qid, ans, adj.criteria());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question_id", qid.toString());
                m.put("auto_score", ans.getFinalScore());
                m.put("sme_score", sme);
                m.put("reason", adj.reason().trim());
                if (criteria != null) m.put("criteria", criteria);
                applied.add(m);
                ans.setSmeScore(sme);
                ans.setSmeReason(adj.reason().trim());
                ans.setSmeCriteria(criteria);
                ans.setFinalScore(sme);
                answers.save(ans);
            }
            recompute(a, byQuestion.values(), adjusted);

            boolean overridden = false;
            if (!blank(req.theory_band())) overridden |= !override(req.theory_band(), BANDS, "theory_band").equals(a.getTheoryBand());
            if (!blank(req.hands_on_band())) overridden |= !override(req.hands_on_band(), BANDS, "hands_on_band").equals(a.getHandsOnBand());
            if (!blank(req.recommendation())) overridden |= !override(req.recommendation(), RECOMMENDATIONS, "recommendation").equals(a.getRecommendation());
            if (!blank(req.theory_band())) a.setTheoryBand(req.theory_band().trim().toUpperCase(Locale.ROOT));
            if (!blank(req.hands_on_band())) a.setHandsOnBand(req.hands_on_band().trim().toUpperCase(Locale.ROOT));
            if (!blank(req.recommendation())) a.setRecommendation(req.recommendation().trim().toUpperCase(Locale.ROOT));

            r.setAdjustments(applied);
            r.setFinalTheoryBand(a.getTheoryBand());
            r.setFinalHandsOnBand(a.getHandsOnBand());
            r.setFinalRecommendation(a.getRecommendation());
            r.setStatus(applied.isEmpty() && !overridden ? SmeReview.Status.APPROVED : SmeReview.Status.ADJUSTED);
            a.setStatus(AttemptStatus.VALIDATED);
            a.setValidationReason(null);
            attempts.save(a);
            reviews.save(r);
            log(r, actor, "SIGNED_OFF", before, state(r, a));
            activity.record(r.getJobId(), ActivityRecorder.VALIDATION, ActivityRecorder.who(a.getCandidateName(), a.getCandidateEmail())
                    + " result validated by " + actor.getName() + ": " + a.getRecommendation(), a.getId());
            return row(r);
        });
    }

    /** Validates SME criterion scores against the engine's graded criteria (id → max); null when none given. */
    private static Map<String, BigDecimal> criteria(UUID qid, AssessmentAnswer ans, List<CriterionScore> given) {
        if (given == null || given.isEmpty()) return null;
        record Graded(String id, double max) {}
        Map<String, Graded> graded = new HashMap<>();
        for (Map<String, Object> e : ans.getEvidence()) {
            if ("criterion".equals(e.get("kind")) && e.get("criterion") != null && e.get("max") instanceof Number max) {
                String id = String.valueOf(e.get("criterion"));
                graded.put(id.toLowerCase(Locale.ROOT), new Graded(id, max.doubleValue()));
            }
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (CriterionScore c : given) {
            Graded g = c.criterion() == null ? null : graded.get(c.criterion().trim().toLowerCase(Locale.ROOT));
            if (g == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Question " + qid + " has no graded criterion '" + c.criterion() + "'.");
            if (c.sme_score() == null || c.sme_score().signum() < 0 || c.sme_score().doubleValue() > g.max()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Criterion " + g.id() + " of " + qid + " must be scored between 0 and " + g.max() + ".");
            }
            out.put(g.id(), c.sme_score().setScale(2, RoundingMode.HALF_UP));
        }
        return out;
    }

    /** Scores, bands, gate, evidence and template summary from the stored (now adjusted) answers. */
    private void recompute(AssessmentAttempt a, Collection<AssessmentAnswer> all, Set<UUID> adjusted) {
        boolean technical = roleProfiles.findById(a.getJob().getId())
                .map(p -> !Boolean.FALSE.equals(p.getIsTechnical())).orElse(true);
        List<AssessmentAnswer> ordered = all.stream().sorted(Comparator.comparingInt(x -> x.getQuestion().getSeq())).toList();
        List<ScoringRules.Scored> scored = new ArrayList<>();
        List<ResultNarrator.Question> narrated = new ArrayList<>();
        Set<String> gateFlags = new HashSet<>();
        for (AssessmentAnswer ans : ordered) {
            AssessmentQuestion q = ans.getQuestion();
            double earned = ans.getFinalScore() == null ? 0 : ans.getFinalScore().doubleValue();
            scored.add(new ScoringRules.Scored(q.getType(), ScoringRules.dimensionFor(q.getType(), q.getDimension(), technical),
                    q.getCompetency() != null ? q.getCompetency() : q.getType().competency(), earned, q.getPoints()));
            narrated.add(new ResultNarrator.Question(q.getSeq(), q.getType(), earned, q.getPoints(), ans.getEvidence(), ans.getFlags()));
            // The SME has looked at an adjusted question, so its flags no longer force HR_REVIEW.
            if (!adjusted.contains(q.getId())) gateFlags.addAll(ans.getFlags());
        }
        List<String> lines = ResultNarrator.evidenceLines(narrated);
        List<String> weak = new ArrayList<>();
        for (int i = 0; i < narrated.size(); i++) {
            if (narrated.get(i).earned() < narrated.get(i).points() / 2.0) weak.add(lines.get(i));
        }
        long tabs = events.countByAttemptIdAndType(a.getId(), AttemptEventType.TAB_SWITCH);
        ScoringRules.Result res = ScoringRules.score(scored, a.getJob().getScoringSettings(), gateFlags, tabs);
        a.setTheoryScore(res.theory());
        a.setHandsOnScore(res.handsOn());
        a.setOverallScore(res.overall());
        a.setTheoryBand(res.theoryBand());
        a.setHandsOnBand(res.handsOnBand());
        a.setCompetencyScores(new LinkedHashMap<>(res.competencies()));
        a.setRecommendation(res.recommendation());
        a.setEvidenceLines(new ArrayList<>(lines));
        Set<String> flags = new TreeSet<>(a.getFlags());
        flags.addAll(res.flags());
        a.setFlags(new ArrayList<>(flags));
        a.setSummary(ResultNarrator.template(new ResultNarrator.Facts(res.theory(), res.handsOn(), res.overall(),
                res.theoryBand(), res.handsOnBand(), res.recommendation(), List.copyOf(flags), res.competencies(), lines, weak)));
    }

    // ── admin reopen ────────────────────────────────────────────────────

    public Map<String, Object> reopen(UUID reviewId, Reopen req, User actor) {
        return tx.execute(s -> {
            if (req == null || blank(req.reason())) throw new ApiException(HttpStatus.BAD_REQUEST, "A reason is required to reopen.");
            SmeReview r = lock(reviewId);
            if (!r.getStatus().isClosed()) throw new ApiException(HttpStatus.CONFLICT, "This review is still open.", "review_open");
            AssessmentAttempt a = r.getSubjectType() == SmeReview.Subject.ATTEMPT
                    ? attempts.lockById(r.getSubjectId()).orElse(null) : null;
            Map<String, Object> before = a == null ? state(r) : state(r, a);
            r.setStatus(r.getReviewerId() == null ? SmeReview.Status.PENDING : SmeReview.Status.IN_REVIEW);
            r.setSignedOffAt(null);
            reviews.save(r);
            if (a != null) {
                a.setStatus(AttemptStatus.AWAITING_REVIEW);
                attempts.save(a);
            }
            Map<String, Object> after = new LinkedHashMap<>(a == null ? state(r) : state(r, a));
            after.put("reason", req.reason().trim());
            log(r, actor, "REOPENED", before, after);
            return row(r);
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> row(SmeReview r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("review_id", r.getId().toString());
        m.put("subject_type", r.getSubjectType().name());
        m.put("subject_id", r.getSubjectId().toString());
        m.put("job_id", r.getJobId().toString());
        m.put("job_title", jobs.findById(r.getJobId()).map(Job::getTitle).orElse(null));
        m.put("status", r.getStatus().name());
        m.put("reviewer_id", r.getReviewerId() == null ? null : r.getReviewerId().toString());
        m.put("reviewer_name", r.getReviewerId() == null ? null : users.findById(r.getReviewerId()).map(User::getName).orElse(null));
        m.put("due_at", r.getDueAt());
        m.put("overdue", !r.getStatus().isClosed() && r.getDueAt() != null && r.getDueAt().isBefore(Instant.now()));
        m.put("created_at", r.getCreatedAt());
        if (r.getSubjectType() == SmeReview.Subject.ATTEMPT) {
            attempts.findById(r.getSubjectId()).ifPresent(a -> {
                m.put("candidate_email", a.getCandidateEmail());
                m.put("candidate_name", a.getCandidateName());
                m.put("level", a.getLevel());
                m.put("recommendation", a.getRecommendation());
                m.put("attempt_status", a.getStatus().name());
            });
        } else {
            assessments.findById(r.getSubjectId()).ifPresent(x -> m.put("version", x.getVersion()));
        }
        return m;
    }

    private static Map<String, Object> state(SmeReview r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.getStatus().name());
        m.put("reviewer_id", r.getReviewerId() == null ? null : r.getReviewerId().toString());
        return m;
    }

    private static Map<String, Object> state(SmeReview r, AssessmentAttempt a) {
        Map<String, Object> m = state(r);
        m.put("attempt_status", a.getStatus().name());
        m.put("theory_score", a.getTheoryScore());
        m.put("hands_on_score", a.getHandsOnScore());
        m.put("overall_score", a.getOverallScore());
        m.put("theory_band", a.getTheoryBand());
        m.put("hands_on_band", a.getHandsOnBand());
        m.put("recommendation", a.getRecommendation());
        if (!r.getAdjustments().isEmpty()) m.put("adjustments", r.getAdjustments());
        return m;
    }

    private void log(SmeReview r, User actor, String action, Map<String, Object> before, Map<String, Object> after) {
        audit.save(new ReviewAudit(r.getId(), actor == null ? null : actor.getId(), action, before, after));
    }

    private SmeReview review(UUID id) {
        return reviews.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review not found."));
    }

    private SmeReview lock(UUID id) {
        return reviews.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review not found."));
    }

    private static void requireOpen(SmeReview r) {
        if (r.getStatus().isClosed()) {
            throw new ApiException(HttpStatus.CONFLICT, "This review is signed off and locked; an admin can reopen it.", "review_locked");
        }
    }

    private static void requireSubject(SmeReview r, SmeReview.Subject subject) {
        if (r.getSubjectType() != subject) throw new ApiException(HttpStatus.BAD_REQUEST, "This is a " + r.getSubjectType() + " review.");
    }

    private static void requireMine(SmeReview r, User actor) {
        if (r.getReviewerId() != null && !r.getReviewerId().equals(actor.getId()) && actor.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Another reviewer has claimed this review.", "claimed_by_other");
        }
    }

    private static String override(String value, Set<String> allowed, String field) {
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) throw new ApiException(HttpStatus.BAD_REQUEST, field + " must be one of " + new TreeSet<>(allowed) + ".");
        return v;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown " + field + ": " + raw);
        }
    }

    private static UUID uuid(String raw, String field) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " must be a UUID.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
