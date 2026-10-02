package com.smartstaff.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.CalibrationRun;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.CalibrationRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** F15.4: a calibration set is a list of SME-graded answers to existing
 *  assessment questions. Importing one stores it and scores every answer with
 *  the current engine (CalibrationRunJob), so the agreement can be checked
 *  before and after a prompt or rule change; a re-run scores the same set again. */
@Service
public class CalibrationSetService {

    public static final int MAX_ITEMS = 200;

    public record Answer(List<Integer> selected_indices, String text_answer, String code, String language) {}

    /** sme_criteria (optional): the SME's score per rubric criterion (CODE_WRITE criteria, SCENARIO KP1..n). */
    public record Item(String question_id, Answer answer, BigDecimal sme_score, Map<String, BigDecimal> sme_criteria, String note) {}

    public record ImportRequest(String name, List<Item> items) {}

    private final CalibrationRunRepository runs;
    private final AssessmentQuestionRepository questions;
    private final CalibrationRunJob job;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public CalibrationSetService(CalibrationRunRepository runs, AssessmentQuestionRepository questions, CalibrationRunJob job,
                                 ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.questions = questions;
        this.job = job;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> importSet(ImportRequest req, User actor) {
        if (req == null || req.name() == null || req.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "name is required.");
        if (req.name().trim().length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "name must be at most 200 characters.");
        List<Map<String, Object>> items = validate(req.items());
        return start(req.name().trim(), items, null, actor);
    }

    public Map<String, Object> rerun(UUID runId, User actor) {
        CalibrationRun source = runs.findById(runId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Calibration run not found."));
        return start(source.getName(), new ArrayList<>(source.getItems()), source.getId(), actor);
    }

    private Map<String, Object> start(String name, List<Map<String, Object>> items, UUID sourceRun, User actor) {
        CalibrationRun run = new CalibrationRun();
        run.setName(name);
        run.setCreatedBy(actor.publicId());
        run.setItems(items);
        run.setSourceRun(sourceRun);
        UUID id = runs.save(run).getId();
        job.run(id);   // async; inline in tests
        return get(id);
    }

    public Map<String, Object> list() {
        List<Map<String, Object>> rows = runs.findTop50ByOrderByCreatedAtDesc().stream().map(r -> view(r, false)).toList();
        return Map.of("runs", rows);
    }

    public Map<String, Object> get(UUID runId) {
        return view(runs.findById(runId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Calibration run not found.")), true);
    }

    private static Map<String, Object> view(CalibrationRun r, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId().toString());
        m.put("name", r.getName());
        m.put("status", r.getStatus().name());
        m.put("created_by", r.getCreatedBy());
        m.put("created_at", r.getCreatedAt());
        m.put("finished_at", r.getFinishedAt());
        m.put("source_run", r.getSourceRun() == null ? null : r.getSourceRun().toString());
        m.put("item_count", r.getItems().size());
        m.put("summary", r.getSummary());
        if (r.getError() != null) m.put("error", r.getError());
        if (full) m.put("results", r.getResults());
        return m;
    }

    // ── validation ──────────────────────────────────────────────────────

    private List<Map<String, Object>> validate(List<Item> items) {
        if (items == null || items.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "items is required.");
        if (items.size() > MAX_ITEMS) throw new ApiException(HttpStatus.BAD_REQUEST, "At most " + MAX_ITEMS + " items per calibration set.");
        return tx.execute(s -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) out.add(normalise(items.get(i), "Item " + (i + 1)));
            return out;
        });
    }

    private Map<String, Object> normalise(Item it, String where) {
        if (it == null) throw new ApiException(HttpStatus.BAD_REQUEST, where + " is empty.");
        UUID qid;
        try {
            qid = UUID.fromString(String.valueOf(it.question_id()).trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + ": question_id must be a question id.");
        }
        AssessmentQuestion q = questions.findById(qid)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, where + ": question not found."));
        if (it.answer() == null) throw new ApiException(HttpStatus.BAD_REQUEST, where + ": answer is required.");
        if (it.sme_score() == null || it.sme_score().signum() < 0 || it.sme_score().doubleValue() > q.getPoints()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + ": sme_score must be between 0 and " + q.getPoints() + ".");
        }

        Map<String, BigDecimal> criteria = null;
        if (it.sme_criteria() != null && !it.sme_criteria().isEmpty()) {
            Map<String, RubricGrader.Criterion> graded = new HashMap<>();
            for (RubricGrader.Criterion c : rubricCriteria(q)) graded.put(c.id().toLowerCase(Locale.ROOT), c);
            criteria = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : it.sme_criteria().entrySet()) {
                RubricGrader.Criterion c = graded.get(e.getKey().trim().toLowerCase(Locale.ROOT));
                if (c == null) throw new ApiException(HttpStatus.BAD_REQUEST, where + ": the question has no rubric criterion '" + e.getKey() + "'.");
                if (e.getValue() == null || e.getValue().signum() < 0 || e.getValue().doubleValue() > c.max()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, where + ": criterion " + c.id() + " must be between 0 and " + c.max() + ".");
                }
                criteria.put(c.id(), e.getValue().setScale(2, RoundingMode.HALF_UP));
            }
        }

        Answer a = it.answer();
        Answer answer = new Answer(a.selected_indices() == null ? List.of() : a.selected_indices().stream().filter(Objects::nonNull).toList(),
                a.text_answer(), a.code(), a.language() == null ? null : a.language().trim().toLowerCase(Locale.ROOT));
        Item clean = new Item(qid.toString(), answer, it.sme_score().setScale(2, RoundingMode.HALF_UP), criteria,
                it.note() == null || it.note().isBlank() ? null : it.note().trim());
        return json.convertValue(clean, new TypeReference<Map<String, Object>>() {});
    }

    /** The criteria the engine grades a question against (none for objective and debug types). */
    static List<RubricGrader.Criterion> rubricCriteria(AssessmentQuestion q) {
        return switch (q.getType()) {
            case CODE_WRITE -> RubricGrader.codeCriteria(q.getRubric());
            case SCENARIO -> RubricGrader.keyPointCriteria(q.getKeyPoints());
            default -> List.of();
        };
    }
}
