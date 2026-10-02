package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AssessmentAnswer;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.CalibrationRun;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.CalibrationRunRepository;
import com.smartstaff.util.CalibrationRules;
import com.smartstaff.util.CalibrationRules.Agreement;
import com.smartstaff.util.CalibrationRules.Groups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** F15.4: scores a calibration set with the current engine. Questions are read
 *  in one short transaction; grading (sandbox + Gemini) runs outside it, with
 *  exactly the evaluation rules (EvaluationJob.dryRun); nothing is written to
 *  any attempt. Answers the engine couldn't grade (runner/Gemini down) are
 *  reported as not scored rather than as disagreements. */
@Component
public class CalibrationRunJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRunJob.class);

    private final CalibrationRunRepository runs;
    private final AssessmentQuestionRepository questions;
    private final EvaluationJob evaluation;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public CalibrationRunJob(CalibrationRunRepository runs, AssessmentQuestionRepository questions, EvaluationJob evaluation,
                             ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.questions = questions;
        this.evaluation = evaluation;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Async("scoringExecutor")
    public void run(UUID runId) {
        try {
            execute(runId);
        } catch (Exception e) {
            log.error("Calibration run {} failed", runId, e);
            tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> {
                r.setStatus(CalibrationRun.Status.FAILED);
                r.setError("The calibration run failed; see the server log.");
                r.setFinishedAt(Instant.now());
                runs.save(r);
            }));
        }
    }

    private record Prepared(CalibrationSetService.Item item, String type, Integer points, EvaluationJob.Item engineItem) {}

    private void execute(UUID runId) {
        List<Prepared> prepared = tx.execute(s -> {
            CalibrationRun run = runs.findById(runId).orElse(null);
            if (run == null) return null;
            List<Prepared> out = new ArrayList<>();
            for (Map<String, Object> raw : run.getItems()) {
                CalibrationSetService.Item item = json.convertValue(raw, CalibrationSetService.Item.class);
                AssessmentQuestion q = questions.findById(UUID.fromString(item.question_id())).orElse(null);
                out.add(q == null ? new Prepared(item, null, null, null)
                        : new Prepared(item, q.getType().name(), q.getPoints(), evaluation.item(q, answer(item.answer()))));
            }
            return out;
        });
        if (prepared == null) return;

        Agreement overall = new Agreement();
        Groups byType = new Groups(), byCriterion = new Groups();
        List<Map<String, Object>> results = new ArrayList<>();
        int scored = 0;
        for (int i = 0; i < prepared.size(); i++) {
            Prepared p = prepared.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i + 1);
            m.put("question_id", p.item().question_id());
            m.put("type", p.type());
            m.put("points", p.points());
            m.put("sme_score", p.item().sme_score());
            if (p.item().note() != null) m.put("note", p.item().note());
            results.add(m);
            if (p.engineItem() == null) {
                notScored(m, "The question no longer exists.");
                continue;
            }
            EvaluationJob.DryRun d = evaluation.dryRun(p.engineItem());
            String pending = pendingReason(d);
            if (pending != null) {
                notScored(m, pending);
                continue;
            }
            scored++;
            double engine = d.score().doubleValue(), sme = p.item().sme_score().doubleValue();
            boolean within = CalibrationRules.within(engine, sme, p.points());
            overall.add(engine, sme, p.points());
            byType.get(p.type()).add(engine, sme, p.points());
            m.put("scored", true);
            m.put("engine_score", d.score());
            m.put("diff_pct", p.points() <= 0 ? 0 : Math.round((engine - sme) / p.points() * 1000) / 10.0);
            m.put("within_tolerance", within);
            m.put("engine_flags", d.flags());
            List<Map<String, Object>> criteria = criteria(p, d, byCriterion);
            if (!criteria.isEmpty()) m.put("criteria", criteria);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("items", prepared.size());
        summary.put("scored", scored);
        summary.put("not_scored", prepared.size() - scored);
        summary.put("tolerance_pct", CalibrationRules.TOLERANCE_PCT);
        summary.put("target", CalibrationRules.TARGET);
        summary.put("overall", overall.toMap());
        summary.put("by_type", byType.toList("type"));
        summary.put("by_criterion", byCriterion.toList("criterion"));

        tx.executeWithoutResult(s -> runs.findById(runId).ifPresent(r -> {
            r.setResults(results);
            r.setSummary(summary);
            r.setStatus(CalibrationRun.Status.DONE);
            r.setFinishedAt(Instant.now());
            runs.save(r);
        }));
    }

    /** Engine vs SME per criterion the SME scored. */
    private static List<Map<String, Object>> criteria(Prepared p, EvaluationJob.DryRun d, Groups byCriterion) {
        Map<String, BigDecimal> given = p.item().sme_criteria();
        if (given == null || given.isEmpty()) return List.of();
        Map<String, Map<String, Object>> engine = new HashMap<>();
        for (Map<String, Object> e : d.evidence()) {
            if ("criterion".equals(e.get("kind"))) engine.put(String.valueOf(e.get("criterion")).toLowerCase(Locale.ROOT), e);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        given.forEach((criterion, sme) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("criterion", criterion);
            c.put("sme", sme);
            Map<String, Object> e = engine.get(criterion.toLowerCase(Locale.ROOT));
            if (e != null && e.get("score") instanceof Number s && e.get("max") instanceof Number max) {
                boolean within = CalibrationRules.within(s.doubleValue(), sme.doubleValue(), max.doubleValue());
                byCriterion.get(CalibrationRules.criterionKey(p.type(), criterion)).add(s.doubleValue(), sme.doubleValue(), max.doubleValue());
                c.put("engine", s);
                c.put("max", max);
                c.put("within_tolerance", within);
            } else {
                c.put("engine", null);
                c.put("comment", "The engine didn't grade this criterion (e.g. no rubric credit below 25% of hidden tests).");
            }
            out.add(c);
        });
        return out;
    }

    private static String pendingReason(EvaluationJob.DryRun d) {
        if (!d.flags().contains("RUBRIC_PENDING") && !d.flags().contains("RUNNER_PENDING")) return null;
        return d.evidence().stream().filter(e -> "pending".equals(e.get("kind"))).map(e -> String.valueOf(e.get("text")))
                .findFirst().orElse("The engine couldn't grade this answer right now.");
    }

    private static void notScored(Map<String, Object> m, String reason) {
        m.put("scored", false);
        m.put("not_scored_reason", reason);
    }

    /** An unsaved answer carrying the calibration answer, for EvaluationJob.item. */
    private static AssessmentAnswer answer(CalibrationSetService.Answer in) {
        AssessmentAnswer a = new AssessmentAnswer();
        if (in.selected_indices() != null) a.setSelectedIndices(new ArrayList<>(in.selected_indices()));
        a.setTextAnswer(in.text_answer());
        a.setCode(in.code());
        a.setLanguage(in.language());
        return a;
    }
}
