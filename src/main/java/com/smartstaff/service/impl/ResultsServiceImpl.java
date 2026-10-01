package com.smartstaff.service.impl;

import com.smartstaff.dto.response.AssessmentSubmissionsResponse;
import com.smartstaff.dto.response.AssessmentSubmissionsResponse.SubmissionSummary;
import com.smartstaff.dto.response.AttemptReviewResponse;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.*;
import com.smartstaff.service.ResultsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ResultsServiceImpl implements ResultsService {

    private final JobRepository jobs;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentAnswerRepository answers;
    private final AssessmentQuestionRepository questions;
    private final AssessmentScorecardRepository scorecards;
    private final CodeRunRepository codeRuns;
    private final AttemptEventRepository events;

    public ResultsServiceImpl(JobRepository jobs, AssessmentAttemptRepository attempts, AssessmentAnswerRepository answers,
                              AssessmentQuestionRepository questions, AssessmentScorecardRepository scorecards,
                              CodeRunRepository codeRuns, AttemptEventRepository events) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.answers = answers;
        this.questions = questions;
        this.scorecards = scorecards;
        this.codeRuns = codeRuns;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentSubmissionsResponse submissions(UUID jobId) {
        return new AssessmentSubmissionsResponse(true,
                attempts.findByJobIdOrderByStartedAtDesc(jobId).stream().map(this::row).toList(), EvaluationJob.PASS_THRESHOLD);
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentSubmissionsResponse submissionsAll(User requester) {
        List<UUID> ids = (requester.getRole() == Role.ADMIN
                ? jobs.findAllByOrderByCreatedAtDesc()
                : jobs.findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(requester.publicId()))
                .stream().map(Job::getId).toList();
        if (ids.isEmpty()) return AssessmentSubmissionsResponse.empty();
        return new AssessmentSubmissionsResponse(true,
                attempts.findByJobIdInOrderByStartedAtDesc(ids).stream().map(this::row).toList(), EvaluationJob.PASS_THRESHOLD);
    }

    private SubmissionSummary row(AssessmentAttempt a) {
        int numQuestions = (int) questions.countByAssessmentIdAndLevel(a.getAssessment().getId(), a.getLevel());
        AssessmentScorecard card = scorecards.findByAttemptId(a.getId()).orElse(null);
        boolean scored = hasResult(a.getStatus());
        return new SubmissionSummary(
                a.getId().toString(),
                a.getJob().getId().toString(),
                a.getJob().getTitle(),
                a.getCandidateEmail(),
                a.getCandidateName(),
                a.getLevel(),
                List.of(a.getLevel()),
                a.getInvite().isCombined(),
                a.getStatus().name(),
                (int) answers.findByAttemptId(a.getId()).stream().filter(ResultsServiceImpl::hasContent).count(),
                numQuestions,
                a.getStartedAt(),
                a.getSubmittedAt(),
                a.isAutoSubmitted(),
                a.getVersion(),
                scored ? a.getTheoryScore() : null,
                scored ? a.getHandsOnScore() : null,
                scored ? a.getOverallScore() : null,
                scored ? a.getTheoryBand() : null,
                scored ? a.getHandsOnBand() : null,
                scored ? a.getRecommendation() : null,
                List.copyOf(a.getFlags()),
                resultStatus(a.getStatus()),
                reviewStatus(a.getStatus()),
                card == null ? null : card.getStatus().name(),
                card == null ? null : card.getPercent(),
                card == null ? null : card.isPassed(),
                card == null ? null : card.isNeedsReview());
    }

    private static boolean hasResult(AttemptStatus s) {
        return s == AttemptStatus.EVALUATED || s == AttemptStatus.AWAITING_REVIEW || s == AttemptStatus.VALIDATED;
    }

    /** PROVISIONAL until an SME signs off (F12). */
    private static String resultStatus(AttemptStatus s) {
        return s == AttemptStatus.VALIDATED ? "VALIDATED" : hasResult(s) ? "PROVISIONAL" : null;
    }

    private static String reviewStatus(AttemptStatus s) {
        return switch (s) {
            case EVALUATED -> "NOT_REVIEWED";
            case AWAITING_REVIEW -> "AWAITING_REVIEW";
            case VALIDATED -> "VALIDATED";
            default -> null;
        };
    }

    static boolean hasContent(AssessmentAnswer ans) {
        return !ans.getSelectedIndices().isEmpty()
                || (ans.getCode() != null && !ans.getCode().isBlank())
                || (ans.getTextAnswer() != null && !ans.getTextAnswer().isBlank());
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptReviewResponse attempt(UUID attemptId) {
        AssessmentAttempt a = attempts.findById(attemptId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attempt not found."));
        Map<UUID, AssessmentAnswer> byQuestion = new HashMap<>();
        for (AssessmentAnswer ans : answers.findByAttemptId(attemptId)) byQuestion.put(ans.getQuestion().getId(), ans);

        List<AttemptReviewResponse.QuestionReview> qs = new ArrayList<>();
        for (AssessmentQuestion q : questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getAssessment().getId())) {
            if (!a.getLevel().equals(q.getLevel())) continue;
            AssessmentAnswer ans = byQuestion.get(q.getId());
            List<AttemptReviewResponse.RunView> runs = ans == null ? List.of()
                    : codeRuns.findByAnswerIdOrderByCreatedAtAsc(ans.getId()).stream()
                        .map(r -> new AttemptReviewResponse.RunView(r.getMode().name(), r.getLanguage(), r.getCompileError(),
                                List.copyOf(r.getResults()), r.getCreatedAt()))
                        .toList();
            qs.add(new AttemptReviewResponse.QuestionReview(
                    q.getId().toString(), q.getSeq(), q.getType().name(),
                    q.getDimension() == null ? null : q.getDimension().name(),
                    q.getCompetency() == null ? null : q.getCompetency().name(),
                    q.getPoints(),
                    ans == null ? null : ans.getQuestionSnapshot(),
                    ans == null ? null : ans.getSelectedIndices(),
                    ans == null ? null : ans.getLanguage(),
                    ans == null ? null : ans.getCode(),
                    ans == null ? null : ans.getTextAnswer(),
                    ans == null ? null : ans.getAutoScore(),
                    ans == null ? null : ans.getFinalScore(),
                    ans == null ? List.of() : ans.getFlags(),
                    ans == null ? List.of() : ans.getEvidence(),
                    ans == null ? null : ans.getRubricScore(),
                    runs));
        }

        List<AttemptEvent> evs = events.findByAttemptIdOrderByAtAsc(attemptId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AttemptEventType t : AttemptEventType.values()) counts.put(t.name(), 0L);
        evs.forEach(e -> counts.merge(e.getType().name(), 1L, Long::sum));

        boolean scored = hasResult(a.getStatus());
        return new AttemptReviewResponse(true, row(a),
                scored ? a.getCompetencyScores() : null,
                scored ? a.getEvidenceLines() : null,
                scored ? a.getSummary() : null,
                qs, counts,
                evs.stream().map(e -> new AttemptReviewResponse.EventView(e.getType().name(), e.getPayload(), e.getAt())).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ScoringSettings scoringSettings(UUID jobId) {
        return ScoringSettings.orDefaults(job(jobId).getScoringSettings());
    }

    @Override
    @Transactional
    public ScoringSettings updateScoringSettings(UUID jobId, ScoringSettings settings) {
        if (settings == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Scoring settings are required.");
        String problem = settings.problem();
        if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem);
        Job job = job(jobId);
        job.setScoringSettings(settings);
        jobs.save(job);
        return settings;
    }

    private Job job(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
    }
}
