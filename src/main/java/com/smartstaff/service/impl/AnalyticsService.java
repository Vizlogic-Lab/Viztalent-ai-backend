package com.smartstaff.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.QuestionBankItem;
import com.smartstaff.entity.ReviewPolicy;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.QuestionBankItemRepository;
import com.smartstaff.util.CalibrationRules;
import com.smartstaff.util.CalibrationRules.Agreement;
import com.smartstaff.util.CalibrationRules.Groups;
import com.smartstaff.util.CalibrationRules.Observation;
import com.smartstaff.util.CalibrationRules.QuestionStats;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** F15: calibration (engine vs SME) and question-quality analytics.
 *
 *  An engine/SME pair is one question of an attempt an SME signed off
 *  (review APPROVED or ADJUSTED): engine = attempt_answers.engine_score, SME =
 *  final_score (the SME's adjusted score, or the engine's one they accepted).
 *  Questions the engine couldn't grade (*_PENDING / *_UNAVAILABLE) are counted
 *  as ungraded, not as disagreements. HR preview attempts (@viztalent.internal)
 *  never count. */
@Service
public class AnalyticsService {

    private static final Set<String> UNGRADED = Set.of("RUBRIC_PENDING", "RUNNER_PENDING", "RUBRIC_UNAVAILABLE", "RUNNER_UNAVAILABLE");
    private static final String NOT_PREVIEW = " and lower(t.candidate_email) not like '%@viztalent.internal'";
    private static final double CHANGED = 0.005;
    private static final int MOST_ADJUSTED = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JobRepository jobs;
    private final QuestionBankItemRepository bank;
    private final TransactionTemplate tx;

    public AnalyticsService(JdbcTemplate jdbc, ObjectMapper json, JobRepository jobs, QuestionBankItemRepository bank,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.jobs = jobs;
        this.bank = bank;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ── 1 + 3: engine vs SME ────────────────────────────────────────────

    private static final class QuestionAdjustments {
        String jobId, jobTitle, type, prompt, bankItemId;
        int pairs, adjusted;
        double absPct;
    }

    public Map<String, Object> calibration(UUID jobId) {
        String jobFilter = jobId == null ? "" : " and t.job_id = ?";
        Object[] args = jobId == null ? new Object[0] : new Object[]{jobId};

        Agreement overall = new Agreement();
        Groups byType = new Groups(), byCriterion = new Groups(), byJob = new Groups();
        Map<String, String> jobTitles = new HashMap<>();
        Map<String, QuestionAdjustments> perQuestion = new LinkedHashMap<>();
        int[] ungraded = {0};

        jdbc.query("select a.question_id, q.type, q.points, q.prompt, q.bank_item_id, t.job_id, j.title, "
                + "a.engine_score, a.final_score, a.flags::text as flags, a.evidence::text as evidence, a.sme_criteria::text as sme_criteria "
                + "from attempt_answers a "
                + "join assessment_attempts t on t.id = a.attempt_id "
                + "join assessment_questions q on q.id = a.question_id "
                + "join jobs j on j.id = t.job_id "
                + "join sme_reviews r on r.subject_type = 'ATTEMPT' and r.subject_id = t.id "
                + "where t.status = 'VALIDATED' and r.status in ('APPROVED', 'ADJUSTED') "
                + "and a.engine_score is not null and a.final_score is not null" + NOT_PREVIEW + jobFilter, rs -> {
            if (stringList(rs.getString("flags")).stream().anyMatch(UNGRADED::contains)) {
                ungraded[0]++;
                return;
            }
            String type = rs.getString("type");
            int points = rs.getInt("points");
            double engine = rs.getDouble("engine_score"), sme = rs.getDouble("final_score");
            String job = rs.getString("job_id");
            jobTitles.put(job, rs.getString("title"));
            overall.add(engine, sme, points);
            byType.get(type).add(engine, sme, points);
            byJob.get(job).add(engine, sme, points);

            boolean adjusted = Math.abs(engine - sme) > CHANGED;
            QuestionAdjustments qa = perQuestion.computeIfAbsent(rs.getString("question_id"), k -> new QuestionAdjustments());
            qa.jobId = job;
            qa.jobTitle = rs.getString("title");
            qa.type = type;
            qa.prompt = excerpt(rs.getString("prompt"));
            qa.bankItemId = rs.getString("bank_item_id");
            qa.pairs++;
            if (adjusted) qa.adjusted++;
            qa.absPct += points <= 0 ? 0 : Math.abs(engine - sme) / points * 100;

            Map<String, Double> smeCriteria = lowerKeys(numberMap(rs.getString("sme_criteria")));
            for (Map<String, Object> e : maps(rs.getString("evidence"))) {
                if (!"criterion".equals(e.get("kind")) || !(e.get("score") instanceof Number s) || !(e.get("max") instanceof Number m)) continue;
                String criterion = String.valueOf(e.get("criterion"));
                Agreement group = byCriterion.get(CalibrationRules.criterionKey(type, criterion));
                Double given = smeCriteria.get(criterion.toLowerCase(Locale.ROOT));
                if (given != null) group.add(s.doubleValue(), given, m.doubleValue());
                else if (!adjusted) group.add(s.doubleValue(), s.doubleValue(), m.doubleValue());   // the SME accepted it
                else group.unattributed();
            }
        }, args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tolerance_pct", CalibrationRules.TOLERANCE_PCT);
        out.put("target", CalibrationRules.TARGET);
        out.put("overall", overall.toMap());
        out.put("ungraded", ungraded[0]);
        out.put("by_type", byType.toList("type"));
        out.put("by_criterion", byCriterion.toList("criterion"));
        List<Map<String, Object>> jobRows = byJob.toList("job_id");
        jobRows.forEach(m -> m.put("job_title", jobTitles.get((String) m.get("job_id"))));
        out.put("by_job", jobRows);
        out.put("most_adjusted", mostAdjusted(perQuestion));

        Recommendations recs = recommendations(jobFilter, args);
        out.put("recommendations", recs.agreement());
        out.put("sample_rate", sampleRate(jobId, recs.notRecommendedByJob()));
        return out;
    }

    private static List<Map<String, Object>> mostAdjusted(Map<String, QuestionAdjustments> perQuestion) {
        return perQuestion.entrySet().stream()
                .filter(e -> e.getValue().adjusted > 0)
                .sorted(Comparator.comparingInt((Map.Entry<String, QuestionAdjustments> e) -> -e.getValue().adjusted)
                        .thenComparingDouble(e -> -e.getValue().absPct / e.getValue().pairs))
                .limit(MOST_ADJUSTED)
                .map(e -> {
                    QuestionAdjustments qa = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("question_id", e.getKey());
                    m.put("job_id", qa.jobId);
                    m.put("job_title", qa.jobTitle);
                    m.put("type", qa.type);
                    m.put("prompt", qa.prompt);
                    m.put("bank_item_id", qa.bankItemId);
                    m.put("reviews", qa.pairs);
                    m.put("adjusted", qa.adjusted);
                    m.put("mean_abs_diff_pct", Math.round(qa.absPct / qa.pairs * 100) / 100.0);
                    return m;
                })
                .toList();
    }

    private record Recommendations(List<Map<String, Object>> agreement, Map<UUID, List<Boolean>> notRecommendedByJob) {}

    /** How often the SME kept the engine's recommendation, per engine recommendation; newest first. */
    private Recommendations recommendations(String jobFilter, Object[] args) {
        Map<String, int[]> counts = new TreeMap<>();
        Map<UUID, List<Boolean>> notRecommended = new LinkedHashMap<>();
        jdbc.query("select t.job_id, t.engine_recommendation as engine, coalesce(r.final_recommendation, t.recommendation) as sme "
                + "from sme_reviews r join assessment_attempts t on t.id = r.subject_id "
                + "where r.subject_type = 'ATTEMPT' and r.status in ('APPROVED', 'ADJUSTED') and t.status = 'VALIDATED' "
                + "and t.engine_recommendation is not null" + NOT_PREVIEW + jobFilter
                + " order by r.signed_off_at desc nulls last, r.created_at desc", rs -> {
            String engine = rs.getString("engine");
            boolean agreed = engine.equals(rs.getString("sme"));
            int[] c = counts.computeIfAbsent(engine, k -> new int[2]);
            c[0]++;
            if (agreed) c[1]++;
            if ("NOT_RECOMMENDED".equals(engine)) {
                notRecommended.computeIfAbsent(rs.getObject("job_id", UUID.class), k -> new ArrayList<>()).add(agreed);
            }
        }, args);
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((engine, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine_recommendation", engine);
            m.put("reviews", c[0]);
            m.put("sme_agreed", c[1]);
            m.put("share", Math.round(c[1] * 1000.0 / c[0]) / 1000.0);
            out.add(m);
        });
        return new Recommendations(out, notRecommended);
    }

    /** Per job: should the NOT_RECOMMENDED sample rate go down? Suggestion only. */
    private List<Map<String, Object>> sampleRate(UUID jobId, Map<UUID, List<Boolean>> byJob) {
        Set<UUID> ids = new LinkedHashSet<>(byJob.keySet());
        if (jobId != null) ids.add(jobId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job job : jobs.findAllById(ids)) {
            double current = ReviewPolicy.orDefaults(job.getReviewPolicy()).sample_rate_not_recommended();
            CalibrationRules.SampleSuggestion s = CalibrationRules.sampleRate(byJob.getOrDefault(job.getId(), List.of()), current);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", job.getId().toString());
            m.put("job_title", job.getTitle());
            m.put("current_rate", current);
            m.put("reviews", s.reviews());
            m.put("agreement", s.agreement());
            m.put("suggest", s.suggest());
            m.put("suggested_rate", s.suggestedRate());
            m.put("reason", s.reason());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("job_title")), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ── 2: question quality ─────────────────────────────────────────────

    private record Row(UUID attempt, UUID question, UUID bankItem, String version, String level, String type, int points,
                       String prompt, String origin, String jobId, String jobTitle, double score, Instant submittedAt) {}

    /** Scored answers of evaluated attempts. An answer the engine couldn't grade counts only once an SME scored it. */
    private List<Row> scoredRows(UUID jobId) {
        String jobFilter = jobId == null ? "" : " and t.job_id = ?";
        Object[] args = jobId == null ? new Object[0] : new Object[]{jobId};
        List<Row> rows = new ArrayList<>();
        jdbc.query("select a.attempt_id, a.question_id, q.bank_item_id, s.version, q.level, q.type, q.points, q.prompt, q.origin, "
                + "t.job_id, j.title, a.final_score, a.sme_score, a.flags::text as flags, t.submitted_at "
                + "from attempt_answers a "
                + "join assessment_attempts t on t.id = a.attempt_id "
                + "join assessment_questions q on q.id = a.question_id "
                + "join assessments s on s.id = q.assessment_id "
                + "join jobs j on j.id = t.job_id "
                + "where t.status in ('EVALUATED', 'AWAITING_REVIEW', 'VALIDATED') and a.final_score is not null"
                + NOT_PREVIEW + jobFilter, rs -> {
            if (rs.getObject("sme_score") == null && stringList(rs.getString("flags")).stream().anyMatch(UNGRADED::contains)) return;
            Timestamp submitted = rs.getTimestamp("submitted_at");
            rows.add(new Row(rs.getObject("attempt_id", UUID.class), rs.getObject("question_id", UUID.class),
                    rs.getObject("bank_item_id", UUID.class), rs.getString("version"), rs.getString("level"), rs.getString("type"),
                    rs.getInt("points"), rs.getString("prompt"), rs.getString("origin"), rs.getString("job_id"), rs.getString("title"),
                    rs.getDouble("final_score"), submitted == null ? null : submitted.toInstant()));
        }, args);
        return rows;
    }

    /** One observation per row, with the attempt's share on its other (counted) questions. */
    private static Map<Row, Observation> observations(List<Row> rows) {
        Map<UUID, double[]> totals = new HashMap<>();   // attempt → {earned, possible}
        for (Row r : rows) {
            double[] t = totals.computeIfAbsent(r.attempt(), k -> new double[2]);
            t[0] += r.score();
            t[1] += r.points();
        }
        Map<Row, Observation> out = new IdentityHashMap<>();
        for (Row r : rows) {
            double[] t = totals.get(r.attempt());
            double restPossible = t[1] - r.points();
            out.put(r, new Observation(r.score(), r.points(), restPossible <= 0 ? 0 : (t[0] - r.score()) / restPossible));
        }
        return out;
    }

    public Map<String, Object> questionStats(UUID jobId, boolean flaggedOnly) {
        List<Row> rows = scoredRows(jobId);
        Map<Row, Observation> obs = observations(rows);
        Map<UUID, List<Row>> byQuestion = new LinkedHashMap<>();
        rows.forEach(r -> byQuestion.computeIfAbsent(r.question(), k -> new ArrayList<>()).add(r));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<UUID, List<Row>> e : byQuestion.entrySet()) {
            QuestionStats stats = CalibrationRules.questionStats(e.getValue().stream().map(obs::get).toList());
            if (flaggedOnly && stats.flags().isEmpty()) continue;
            Row first = e.getValue().get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question_id", e.getKey().toString());
            m.put("job_id", first.jobId());
            m.put("job_title", first.jobTitle());
            m.put("version", first.version());
            m.put("level", first.level());
            m.put("type", first.type());
            m.put("origin", first.origin());
            m.put("bank_item_id", first.bankItem() == null ? null : first.bankItem().toString());
            m.put("prompt", excerpt(first.prompt()));
            m.put("points", first.points());
            m.putAll(stats.toMap());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> ((List<?>) m.get("flags")).isEmpty())
                .thenComparing(m -> -(Integer) m.get("attempts")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_attempts_for_flags", CalibrationRules.MIN_ATTEMPTS_FOR_FLAGS);
        body.put("questions", out);
        return body;
    }

    /** Bank items across every job that used them, counting only attempts after the last re-enable. */
    private Map<UUID, QuestionStats> bankStats(Collection<QuestionBankItem> items) {
        List<Row> rows = scoredRows(null);
        Map<Row, Observation> obs = observations(rows);
        Map<UUID, List<Observation>> byItem = new HashMap<>();
        Map<UUID, Instant> windows = new HashMap<>();
        items.forEach(i -> windows.put(i.getId(), i.getQualityReenabledAt()));
        for (Row r : rows) {
            if (r.bankItem() == null || !windows.containsKey(r.bankItem())) continue;
            Instant from = windows.get(r.bankItem());
            if (from != null && (r.submittedAt() == null || !r.submittedAt().isAfter(from))) continue;
            byItem.computeIfAbsent(r.bankItem(), k -> new ArrayList<>()).add(obs.get(r));
        }
        Map<UUID, QuestionStats> out = new HashMap<>();
        for (QuestionBankItem i : items) out.put(i.getId(), CalibrationRules.questionStats(byItem.getOrDefault(i.getId(), List.of())));
        return out;
    }

    public Map<String, Object> bankItems() {
        List<QuestionBankItem> items = bank.findAll();
        Map<UUID, QuestionStats> stats = bankStats(items);
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuestionBankItem i : items) {
            QuestionStats s = stats.get(i.getId());
            if (s.attempts() == 0 && !i.isQualityExcluded() && i.getQualityFlags().isEmpty() && i.getQualityReenabledAt() == null) continue;
            Map<String, Object> m = bankRow(i);
            m.put("stats", s.toMap());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("excluded"))
                .thenComparing(m -> String.valueOf(m.get("prompt"))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_attempts_for_flags", CalibrationRules.MIN_ATTEMPTS_FOR_FLAGS);
        body.put("bank_items", out);
        return body;
    }

    /** Recomputes bank-item flags; a flagged item (20+ attempts) is excluded from generation until an
     *  admin re-enables it. An excluded item keeps the flags that excluded it. */
    public Map<String, Object> refreshBankQuality() {
        List<String> excludedNow = new ArrayList<>();
        int[] checked = {0};
        tx.executeWithoutResult(s -> {
            List<QuestionBankItem> items = bank.findAll();
            Map<UUID, QuestionStats> stats = bankStats(items);
            Instant now = Instant.now();
            for (QuestionBankItem i : items) {
                List<String> flags = stats.get(i.getId()).flags();
                if (!flags.isEmpty()) {
                    if (!i.isQualityExcluded()) excludedNow.add(i.getId().toString());
                    i.setQualityFlags(new ArrayList<>(flags));
                    i.setQualityExcluded(true);
                } else if (!i.isQualityExcluded()) {
                    i.setQualityFlags(new ArrayList<>());
                }
                i.setQualityCheckedAt(now);
                checked[0]++;
            }
            bank.saveAll(items);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", checked[0]);
        out.put("newly_excluded", excludedNow);
        return out;
    }

    public Map<String, Object> enableBankItem(UUID itemId, User actor) {
        QuestionBankItem item = tx.execute(s -> {
            QuestionBankItem i = bank.findById(itemId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bank question not found."));
            i.setQualityExcluded(false);
            i.setQualityFlags(new ArrayList<>());
            i.setQualityReenabledAt(Instant.now());
            i.setQualityReenabledBy(actor.publicId());
            return bank.save(i);
        });
        return bankRow(item);
    }

    private static Map<String, Object> bankRow(QuestionBankItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bank_item_id", i.getId().toString());
        m.put("type", i.getType());
        m.put("level", i.getLevel());
        m.put("skill", i.getSkill());
        m.put("prompt", excerpt(i.getPrompt()));
        m.put("flags", i.getQualityFlags());
        m.put("excluded", i.isQualityExcluded());
        m.put("checked_at", i.getQualityCheckedAt());
        m.put("reenabled_at", i.getQualityReenabledAt());
        m.put("reenabled_by", i.getQualityReenabledBy());
        return m;
    }

    // ── jsonb helpers ───────────────────────────────────────────────────

    private List<String> stringList(String text) throws SQLException {
        return read(text, new TypeReference<List<String>>() {}, List.of());
    }

    private List<Map<String, Object>> maps(String text) throws SQLException {
        return read(text, new TypeReference<List<Map<String, Object>>>() {}, List.of());
    }

    private Map<String, BigDecimal> numberMap(String text) throws SQLException {
        return read(text, new TypeReference<Map<String, BigDecimal>>() {}, Map.of());
    }

    private <T> T read(String text, TypeReference<T> type, T empty) throws SQLException {
        if (text == null || text.isBlank()) return empty;
        try {
            T v = json.readValue(text, type);
            return v == null ? empty : v;
        } catch (Exception e) {
            throw new SQLException("Unreadable jsonb value", e);
        }
    }

    private static Map<String, Double> lowerKeys(Map<String, BigDecimal> m) {
        Map<String, Double> out = new HashMap<>();
        m.forEach((k, v) -> { if (v != null) out.put(k.toLowerCase(Locale.ROOT), v.doubleValue()); });
        return out;
    }

    static String excerpt(String s) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 120 ? t : t.substring(0, 117) + "...";
    }
}
