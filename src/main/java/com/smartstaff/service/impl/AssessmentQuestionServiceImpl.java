package com.smartstaff.service.impl;

import com.smartstaff.dto.request.QuestionEditRequest;
import com.smartstaff.dto.response.AssessmentQuestionResponse;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.service.AssessmentQuestionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/** Single-question regenerate and edit. Neither the Gemini calls (regenerate)
 *  nor the sandbox runs (validation) happen inside a transaction: the question
 *  is read in one short transaction, worked on outside, and written in another.
 *  A failed regenerate or a failed validation leaves the question untouched. */
@Service
public class AssessmentQuestionServiceImpl implements AssessmentQuestionService {

    private final AssessmentQuestionRepository questions;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentGenerationJob generationJob;
    private final QuestionValidator validator;
    private final AssessmentMapper mapper;
    private final TransactionTemplate tx;

    public AssessmentQuestionServiceImpl(AssessmentQuestionRepository questions, AssessmentAttemptRepository attempts,
                                         AssessmentGenerationJob generationJob, QuestionValidator validator,
                                         AssessmentMapper mapper, PlatformTransactionManager transactionManager) {
        this.questions = questions;
        this.attempts = attempts;
        this.generationJob = generationJob;
        this.validator = validator;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── regenerate ──────────────────────────────────────────────────────

    @Override
    public AssessmentQuestionResponse regenerate(UUID questionId) {
        record Target(UUID assessmentId, String level, int seq) {}
        Target t = tx.execute(s -> {
            AssessmentQuestion q = load(questionId);
            requireUnused(q);
            return new Target(q.getAssessment().getId(), q.getLevel(), q.getSeq());
        });

        AssessmentGenerationJob.SlotResult r = generationJob.regenerateSlot(t.assessmentId(), t.level(), t.seq(), questionId);
        if (r.questionId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Couldn't produce a valid replacement: " + r.failure() + " The original question was kept.",
                    "regenerate_failed");
        }
        return tx.execute(s -> mapper.toQuestionResponse(load(r.questionId())));
    }

    // ── edit ────────────────────────────────────────────────────────────

    @Override
    public AssessmentQuestionResponse edit(UUID questionId, QuestionEditRequest req) {
        if (req == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to change.");
        if (req.points() != null && (req.points() < 1 || req.points() > 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Points must be between 1 and 100.");
        }

        // 1. Detached working copy (OSIV is off, so it's detached once this
        //    commits); its tests become a plain list we can change freely.
        AssessmentQuestion copy = tx.execute(s -> {
            AssessmentQuestion q = load(questionId);
            requireUnused(q);
            q.getTestCases().size();   // initialise while attached; never swap an orphan-removal collection here
            return q;
        });
        copy.setTestCases(new ArrayList<>(copy.getTestCases()));   // detached now, so this is just memory
        applyScalars(copy, req);
        if (req.tests() != null) {
            copy.setTestCases(new ArrayList<>());
            addTests(copy, req.tests());
        }

        // 2. Prove it outside any transaction.
        QuestionValidator.Result result = validator.validate(new QuestionDraft(copy, Map.of()), Set.of(), null, false);
        if (!result.ok()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The edit wasn't saved: " + result.reason(), "invalid_question");
        }

        // 3. Write it back. Old test rows are flushed away before the new ones
        //    are inserted, so (question_id, seq) never collides.
        return tx.execute(s -> {
            AssessmentQuestion q = load(questionId);
            requireUnused(q);
            applyScalars(q, req);
            if (req.tests() != null) {
                q.getTestCases().clear();
                questions.flush();
                addTests(q, req.tests());
            }
            if (q.getType() == QuestionType.CODE_OUTPUT) q.setModelAnswer(copy.getModelAnswer());
            q.setValidated(true);
            q.setValidationLog("Edited by HR. " + result.log());
            return mapper.toQuestionResponse(questions.saveAndFlush(q));
        });
    }

    private static void applyScalars(AssessmentQuestion q, QuestionEditRequest r) {
        if (r.title() != null) q.setTitle(r.title().trim());
        if (r.statement() != null) q.setPrompt(r.statement().trim());
        if (r.options() != null) q.setOptions(new ArrayList<>(r.options()));
        if (r.correct_indices() != null) q.setCorrectIndices(new ArrayList<>(r.correct_indices()));
        if (r.points() != null) q.setPoints(r.points());
        if (r.rubric() != null) q.setRubric(new ArrayList<>(r.rubric()));
        if (r.model_answer() != null) q.setModelAnswer(r.model_answer());
        if (r.key_points() != null) q.setKeyPoints(new ArrayList<>(r.key_points()));
        if (r.explanation() != null) q.setExplanation(r.explanation());
        if (r.constraints() != null) q.setConstraints(r.constraints());
        if (r.input_format() != null) q.setInputFormat(r.input_format());
        if (r.output_format() != null) q.setOutputFormat(r.output_format());
        if (r.starter_code() != null) q.setStarterCode(new LinkedHashMap<>(r.starter_code()));
        if (r.reference_solution() != null) q.setReferenceSolution(new LinkedHashMap<>(r.reference_solution()));
        if (r.buggy_code() != null) {
            q.setBuggyCode(new LinkedHashMap<>(r.buggy_code()));
            // The candidate starts from the buggy code.
            if (q.getType() == QuestionType.CODE_DEBUG) q.setStarterCode(new LinkedHashMap<>(r.buggy_code()));
        }
    }

    private static void addTests(AssessmentQuestion q, List<QuestionEditRequest.TestCaseInput> tests) {
        for (QuestionEditRequest.TestCaseInput t : tests) {
            QuestionTestCase tc = new QuestionTestCase();
            tc.setInput(t.input() == null ? "" : t.input());
            tc.setExpectedOutput(t.expected_output() == null ? "" : t.expected_output());
            tc.setVisible(Boolean.TRUE.equals(t.visible()));
            TestCaseCategory category;
            try {
                category = t.category() == null ? TestCaseCategory.BASIC
                        : TestCaseCategory.valueOf(t.category().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown test category: " + t.category());
            }
            tc.setCategory(category);
            tc.setWeight(t.weight() != null && t.weight().signum() > 0 ? t.weight()
                    : category == TestCaseCategory.BASIC ? BigDecimal.ONE : new BigDecimal("1.5"));
            tc.setFloatTolerance(t.float_tolerance());
            q.addTestCase(tc);
        }
    }

    private AssessmentQuestion load(UUID questionId) {
        return questions.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found."));
    }

    private void requireUnused(AssessmentQuestion q) {
        if (attempts.existsByAssessmentId(q.getAssessment().getId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Candidates have already started this version, so its questions are frozen. "
                            + "Regenerate the whole assessment to get a new version.", "version_in_use");
        }
    }
}
