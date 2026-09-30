package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.entity.GenerationProgress.UnfilledSlot;
import com.smartstaff.repository.*;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.service.impl.QuestionDrafter.DraftException;
import com.smartstaff.service.impl.QuestionDrafter.SlotContext;
import com.smartstaff.util.RoleProfileRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** Fills every blueprint slot of one assessment version, in the background.
 *
 *  QUEUED -> GENERATING (a first draft for every slot) -> VALIDATING (each
 *  draft proven by QuestionValidator; a failed draft is regenerated with the
 *  failure reason, up to 3 Gemini attempts per slot, then the question bank is
 *  tried) -> READY, or FAILED when no slot could be filled. A slot nothing can
 *  fill is recorded as unfilled with its reason.
 *
 *  No Gemini or code-runner call happens inside a transaction: each saved
 *  question and each progress update is its own short transaction. The
 *  version becomes the job's current one only when it is READY, so the
 *  previous READY version keeps serving invites meanwhile. */
@Component
public class AssessmentGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(AssessmentGenerationJob.class);
    static final int MAX_GEMINI_ATTEMPTS = 3;
    static final int MAX_BANK_CANDIDATES = 5;

    private final AssessmentRepository assessments;
    private final AssessmentQuestionRepository questions;
    private final JobRepository jobs;
    private final JobRoleProfileRepository profiles;
    private final QuestionBankItemRepository bank;
    private final SettingsService settings;
    private final CodeRunnerService runner;
    private final QuestionDrafter drafter;
    private final QuestionValidator validator;
    private final TransactionTemplate tx;

    public AssessmentGenerationJob(AssessmentRepository assessments, AssessmentQuestionRepository questions,
                                   JobRepository jobs, JobRoleProfileRepository profiles, QuestionBankItemRepository bank,
                                   SettingsService settings, CodeRunnerService runner, QuestionDrafter drafter,
                                   QuestionValidator validator, PlatformTransactionManager transactionManager) {
        this.assessments = assessments;
        this.questions = questions;
        this.jobs = jobs;
        this.profiles = profiles;
        this.bank = bank;
        this.settings = settings;
        this.runner = runner;
        this.drafter = drafter;
        this.validator = validator;
        this.tx = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: when run inline after the queuing commit, that finished
        // transaction is still bound to the thread and would swallow writes.
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Single node: anything still in progress at startup was cut off by a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGenerations() {
        int n = tx.execute(s -> assessments.failInProgress("Generation was interrupted by a server restart. Generate again."));
        if (n > 0) log.warn("Marked {} interrupted assessment generation(s) as FAILED", n);
    }

    @Async("assessmentExecutor")
    public void run(UUID assessmentId) {
        try {
            generate(assessmentId);
        } catch (Exception e) {
            log.error("Assessment generation {} failed", assessmentId, e);
            String ref = MDC.get("requestId");
            finishFailed(assessmentId, "Generation stopped unexpectedly" + (ref == null ? "." : " (ref " + ref + ")."));
        }
    }

    // ── orchestration ───────────────────────────────────────────────────

    private final class Run {
        final UUID assessmentId;
        final String source;
        final Job job;
        final JobRoleProfile profile;
        final Set<String> profileSkills = new HashSet<>();
        final String apiKey;
        final Set<UUID> usedBankItems = new HashSet<>();
        boolean geminiUsable;
        GenerationProgress progress;

        Run(UUID assessmentId, String source, Job job, JobRoleProfile profile, String apiKey, int slots) {
            this.assessmentId = assessmentId;
            this.source = source;
            this.job = job;
            this.profile = profile;
            this.apiKey = apiKey;
            this.geminiUsable = apiKey != null && !apiKey.isBlank();
            this.progress = GenerationProgress.start(slots);
            if (profile.getSkillWeights() != null) {
                profile.getSkillWeights().keySet().forEach(k -> profileSkills.add(k.toLowerCase(Locale.ROOT)));
            }
        }
    }

    private enum Stage { GEMINI, BANK, DONE }

    private static final class SlotWork {
        final SlotContext ctx;
        final boolean bankFirst;
        final List<String> log = new ArrayList<>();
        Stage stage;
        int geminiAttempts;
        Deque<QuestionDraft> bankCandidates;
        QuestionDraft pending;
        String lastFailure;
        String skipReason;

        SlotWork(SlotContext ctx, boolean bankFirst) {
            this.ctx = ctx;
            this.bankFirst = bankFirst;
            this.stage = bankFirst ? Stage.BANK : Stage.GEMINI;
        }

        Blueprint.Slot slot() {
            return ctx.slot();
        }

        String level() {
            return ctx.blueprint().level();
        }
    }

    private void generate(UUID assessmentId) {
        record Loaded(UUID jobId, String source, Map<String, Blueprint> blueprint) {}
        Loaded loaded = tx.execute(s -> assessments.findById(assessmentId)
                .map(a -> new Loaded(a.getJob().getId(), a.getSource(), new LinkedHashMap<>(a.getBlueprint())))
                .orElse(null));
        if (loaded == null) return;
        Job job = jobs.findById(loaded.jobId()).orElse(null);
        if (job == null) return;
        JobRoleProfile profile = profiles.findById(job.getId()).orElseGet(() -> RoleProfileRules.fallback(job));

        int slotCount = loaded.blueprint().values().stream().mapToInt(b -> b.slots().size()).sum();
        Run run = new Run(assessmentId, loaded.source(), job, profile, settings.getGeminiApiKeyOrNull(), slotCount);
        updateStatus(assessmentId, AssessmentStatus.GENERATING, run.progress);

        Set<String> runnable = runner.availableLanguages().keySet();
        List<SlotWork> work = new ArrayList<>();
        for (Blueprint bp : loaded.blueprint().values()) {
            List<String> codeLanguages = bp.languages().stream().filter(runnable::contains).toList();
            for (Blueprint.Slot slot : bp.slots()) {
                boolean bankFirst = "CUSTOM".equals(run.source) || ("MIX".equals(run.source) && slot.seq() % 2 == 0);
                SlotWork w = new SlotWork(new SlotContext(job, profile, bp, slot, codeLanguages), bankFirst);
                if (slot.type().isCode() && codeLanguages.isEmpty()) {
                    w.skipReason = runnable.isEmpty()
                            ? "The code runner isn't available, so coding questions can't be validated. Check the Piston settings."
                            : "None of this job's languages (" + String.join(", ", bp.languages()) + ") is installed in the code runner.";
                }
                work.add(w);
            }
        }

        for (SlotWork w : work) {
            if (w.skipReason == null) w.pending = nextCandidate(run, w);
        }

        updateStatus(assessmentId, AssessmentStatus.VALIDATING, run.progress);
        for (SlotWork w : work) {
            fill(run, w);
        }

        finish(run, loaded.jobId());
    }

    private void fill(Run run, SlotWork w) {
        if (w.skipReason != null) {
            unfilled(run, w, w.skipReason);
            return;
        }
        while (w.pending != null) {
            QuestionDraft draft = w.pending;
            QuestionValidator.Result result = validator.validate(draft, run.profileSkills, w.slot().targetSkill());
            w.log.add(origin(draft) + ": " + result.log());
            if (result.ok()) {
                save(run, w, draft);
                return;
            }
            w.lastFailure = result.reason();
            if (result.runnerUnavailable() && w.slot().type().isCode()) {
                unfilled(run, w, result.reason());
                return;
            }
            w.pending = nextCandidate(run, w);
        }
        unfilled(run, w, w.lastFailure != null ? w.lastFailure : noSourceReason(run));
    }

    /** Next draft for the slot following its strategy (AI-first: Gemini x3 then
     *  bank; bank-first: bank then Gemini x3), or null when nothing is left. */
    private QuestionDraft nextCandidate(Run run, SlotWork w) {
        while (true) {
            switch (w.stage) {
                case BANK -> {
                    if (w.bankCandidates == null) w.bankCandidates = bankCandidates(run, w);
                    QuestionDraft d = w.bankCandidates.poll();
                    if (d != null) return d;
                    w.stage = w.bankFirst ? Stage.GEMINI : Stage.DONE;
                }
                case GEMINI -> {
                    if (!run.geminiUsable || w.geminiAttempts >= MAX_GEMINI_ATTEMPTS) {
                        w.stage = w.bankFirst ? Stage.DONE : Stage.BANK;
                        continue;
                    }
                    w.geminiAttempts++;
                    try {
                        return drafter.draft(run.apiKey, w.ctx, w.lastFailure);
                    } catch (DraftException e) {
                        w.log.add("AI attempt " + w.geminiAttempts + ": " + e.getMessage());
                        w.lastFailure = e.getMessage();
                        if (e.geminiUnavailable()) {
                            run.geminiUsable = false;
                            run.progress = run.progress.withError(
                                    "Gemini was unavailable, so the remaining slots used the question bank only.");
                        }
                    }
                }
                case DONE -> {
                    return null;
                }
            }
        }
    }

    /** Bank questions of the slot's type and level whose skill is the target,
     *  another profile skill, or unset; target-skill matches first. */
    private Deque<QuestionDraft> bankCandidates(Run run, SlotWork w) {
        Blueprint.Slot slot = w.slot();
        String target = slot.targetSkill() == null ? null : slot.targetSkill().toLowerCase(Locale.ROOT);
        List<QuestionBankItem> items = new ArrayList<>();
        for (QuestionBankItem item : bank.findByLevelOrLevelIsNull(w.level())) {
            if (run.usedBankItems.contains(item.getId())) continue;
            QuestionType type;
            try {
                type = QuestionType.fromBankType(item.getType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (type != slot.type()) continue;
            String skill = item.getSkill() == null ? null : item.getSkill().trim().toLowerCase(Locale.ROOT);
            if (skill != null && !skill.isEmpty() && !run.profileSkills.isEmpty()
                    && !skill.equals(target) && !run.profileSkills.contains(skill)) continue;
            items.add(item);
        }
        Collections.shuffle(items);
        items.sort(Comparator.comparingInt(i -> i.getSkill() != null && i.getSkill().trim().equalsIgnoreCase(target) ? 0 : 1));

        Deque<QuestionDraft> out = new ArrayDeque<>();
        for (QuestionBankItem item : items.subList(0, Math.min(MAX_BANK_CANDIDATES, items.size()))) {
            AssessmentQuestion q = QuestionDrafter.baseQuestion(w.level(), slot);
            q.setOrigin("BANK");
            q.setBankItemId(item.getId());
            q.setPrompt(item.getPrompt());
            q.setOptions(new ArrayList<>(item.getOptions()));
            q.setCorrectIndices(new ArrayList<>(item.getCorrectIndices()));
            q.setSkill(item.getSkill());
            if (item.getDifficulty() != null && !item.getDifficulty().isBlank()) q.setDifficulty(item.getDifficulty());
            out.add(new QuestionDraft(q, Map.of()));
        }
        return out;
    }

    // ── persistence (one short transaction each) ────────────────────────

    private void save(Run run, SlotWork w, QuestionDraft draft) {
        AssessmentQuestion q = draft.question();
        q.setValidated(true);
        q.setValidationLog(String.join("\n", w.log));
        if (q.getBankItemId() != null) run.usedBankItems.add(q.getBankItemId());
        run.progress = run.progress.withFilled(w.level());
        GenerationProgress progress = run.progress;
        tx.executeWithoutResult(s -> {
            q.setAssessment(assessments.getReferenceById(run.assessmentId));
            questions.save(q);
            assessments.findById(run.assessmentId).ifPresent(a -> a.setProgress(progress));
        });
    }

    private void unfilled(Run run, SlotWork w, String reason) {
        run.progress = run.progress.withUnfilled(
                new UnfilledSlot(w.level(), w.slot().seq(), w.slot().type(), w.slot().targetSkill(), reason));
        GenerationProgress progress = run.progress;
        tx.executeWithoutResult(s -> assessments.findById(run.assessmentId).ifPresent(a -> a.setProgress(progress)));
    }

    private void updateStatus(UUID assessmentId, AssessmentStatus status, GenerationProgress progress) {
        tx.executeWithoutResult(s -> assessments.findById(assessmentId).ifPresent(a -> {
            a.setStatus(status);
            a.setProgress(progress);
        }));
    }

    private void finish(Run run, UUID jobId) {
        if (run.progress.filledCount() == 0) {
            GenerationProgress progress = run.progress;
            tx.executeWithoutResult(s -> assessments.findById(run.assessmentId).ifPresent(a -> {
                a.setProgress(progress);
                a.setStatus(AssessmentStatus.FAILED);
                a.setError("No question could be generated or found in the question bank. See the unfilled slots.");
            }));
            return;
        }
        GenerationProgress progress = run.progress;
        tx.executeWithoutResult(s -> {
            jobs.lockById(jobId);
            assessments.clearCurrent(jobId);
            assessments.findById(run.assessmentId).ifPresent(a -> {
                a.setProgress(progress);
                a.setStatus(AssessmentStatus.READY);
                a.setGeneratedAt(Instant.now());
                a.setCurrent(true);
                a.setError(null);
            });
        });
    }

    private void finishFailed(UUID assessmentId, String error) {
        try {
            tx.executeWithoutResult(s -> assessments.findById(assessmentId).ifPresent(a -> {
                a.setStatus(AssessmentStatus.FAILED);
                a.setError(error);
            }));
        } catch (Exception e) {
            log.error("Could not mark assessment {} as failed", assessmentId, e);
        }
    }

    private static String origin(QuestionDraft d) {
        return "BANK".equals(d.question().getOrigin()) ? "Question bank" : "AI draft";
    }

    private static String noSourceReason(Run run) {
        return run.geminiUsable || (run.apiKey != null && !run.apiKey.isBlank())
                ? "Nothing suitable was generated or found in the question bank."
                : "No Gemini key is configured and the question bank has nothing for this type, level and skill.";
    }
}
