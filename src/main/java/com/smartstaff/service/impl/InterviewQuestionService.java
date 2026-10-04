package com.smartstaff.service.impl;

import com.smartstaff.entity.Dimension;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.JobInterviewQuestion;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobInterviewQuestionRepository;
import com.smartstaff.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** F16: a job's own L1 interview questions (asked first, in order) and how many
 *  questions an interview has in total (the AI fills the rest). Edits apply to
 *  interviews prepared afterwards; prepared interviews keep their copy. */
@Service
public class InterviewQuestionService {

    public static final int MAX_QUESTIONS = 10;
    public static final int DEFAULT_TOTAL = 5;
    static final int MAX_EXPECTED_POINTS = 6;

    public record QuestionInput(String question, String category, String skill, String dimension, List<String> expected_points) {}

    public record Config(Integer total_questions) {}

    public record Order(List<String> question_ids) {}

    private final JobInterviewQuestionRepository questions;
    private final JobRepository jobs;

    public InterviewQuestionService(JobInterviewQuestionRepository questions, JobRepository jobs) {
        this.questions = questions;
        this.jobs = jobs;
    }

    /** The configured total, defaulted and clamped to what an interview can hold. */
    public static int total(Job job) {
        Integer t = job.getInterviewQuestionTotal();
        return t == null ? DEFAULT_TOTAL : Math.max(1, Math.min(MAX_QUESTIONS, t));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID jobId) {
        Job job = job(jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total_questions", total(job));
        out.put("max_questions", MAX_QUESTIONS);
        out.put("questions", questions.findByJobIdOrderBySeqAsc(jobId).stream().map(InterviewQuestionService::row).toList());
        return out;
    }

    @Transactional
    public Map<String, Object> create(UUID jobId, QuestionInput in, User actor) {
        job(jobId);
        List<JobInterviewQuestion> existing = questions.findByJobIdOrderBySeqAsc(jobId);
        if (existing.size() >= MAX_QUESTIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A job can have at most " + MAX_QUESTIONS + " interview questions.");
        }
        if (in == null || blank(in.question())) throw new ApiException(HttpStatus.BAD_REQUEST, "question is required.");
        JobInterviewQuestion q = new JobInterviewQuestion();
        q.setJobId(jobId);
        q.setSeq(existing.size());
        q.setCreatedBy(actor == null ? null : actor.publicId());
        apply(q, in);
        return row(questions.save(q));
    }

    @Transactional
    public Map<String, Object> update(UUID jobId, UUID questionId, QuestionInput in) {
        JobInterviewQuestion q = question(jobId, questionId);
        if (in == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to update.");
        if (in.question() != null && in.question().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "question can't be empty.");
        apply(q, in);
        q.setUpdatedAt(Instant.now());
        return row(questions.save(q));
    }

    @Transactional
    public Map<String, Object> delete(UUID jobId, UUID questionId) {
        questions.delete(question(jobId, questionId));
        questions.flush();
        resequence(questions.findByJobIdOrderBySeqAsc(jobId));
        return list(jobId);
    }

    @Transactional
    public Map<String, Object> reorder(UUID jobId, Order order) {
        List<JobInterviewQuestion> current = questions.findByJobIdOrderBySeqAsc(jobId);
        Map<String, JobInterviewQuestion> byId = new HashMap<>();
        current.forEach(q -> byId.put(q.getId().toString(), q));
        List<String> ids = order == null || order.question_ids() == null ? List.of() : order.question_ids();
        if (ids.size() != current.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "question_ids must list every interview question of the job exactly once.");
        }
        resequence(ids.stream().map(byId::get).toList());
        return list(jobId);
    }

    @Transactional
    public Map<String, Object> configure(UUID jobId, Config config) {
        Job job = job(jobId);
        Integer total = config == null ? null : config.total_questions();
        if (total != null && (total < 1 || total > MAX_QUESTIONS)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "total_questions must be between 1 and " + MAX_QUESTIONS + ".");
        }
        job.setInterviewQuestionTotal(total);
        jobs.save(job);
        return list(jobId);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void apply(JobInterviewQuestion q, QuestionInput in) {
        if (in.question() != null) q.setQuestion(limit(in.question().strip(), 1000, "question"));
        if (in.category() != null) q.setCategory(in.category().isBlank() ? null : limit(in.category().strip(), 64, "category"));
        if (in.skill() != null) q.setSkill(in.skill().isBlank() ? null : limit(in.skill().strip(), 128, "skill"));
        if (in.dimension() != null) {
            try {
                q.setDimension(Dimension.valueOf(in.dimension().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "dimension must be THEORY or HANDS_ON.");
            }
        }
        if (in.expected_points() != null) {
            List<String> points = cleanPoints(in.expected_points());
            if (points.size() > MAX_EXPECTED_POINTS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "At most " + MAX_EXPECTED_POINTS + " expected points per question.");
            }
            for (String p : points) limit(p, 300, "Each expected point");
            q.setExpectedPoints(new ArrayList<>(points));
            q.setExpectedPointsSource(points.isEmpty() ? null : "HR");
        }
    }

    /** Trimmed, non-blank, de-duplicated (case-insensitively), in order. */
    static List<String> cleanPoints(List<String> raw) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : raw) {
            if (p == null || p.isBlank()) continue;
            String t = p.strip().replaceAll("\\s+", " ");
            if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    private void resequence(List<JobInterviewQuestion> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            JobInterviewQuestion q = ordered.get(i);
            if (q.getSeq() != i) {
                q.setSeq(i);
                q.setUpdatedAt(Instant.now());
                questions.save(q);
            }
        }
    }

    static Map<String, Object> row(JobInterviewQuestion q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question_id", q.getId().toString());
        m.put("seq", q.getSeq());
        m.put("question", q.getQuestion());
        m.put("category", q.getCategory());
        m.put("skill", q.getSkill());
        m.put("dimension", q.getDimension().name());
        m.put("expected_points", q.getExpectedPoints());
        m.put("expected_points_source", q.getExpectedPointsSource());
        m.put("created_by", q.getCreatedBy());
        m.put("updated_at", q.getUpdatedAt());
        return m;
    }

    private Job job(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
    }

    private JobInterviewQuestion question(UUID jobId, UUID questionId) {
        JobInterviewQuestion q = questions.findById(questionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interview question not found."));
        if (!q.getJobId().equals(jobId)) throw new ApiException(HttpStatus.NOT_FOUND, "Interview question not found.");
        return q;
    }

    private static String limit(String s, int max, String what) {
        if (s.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST, what + " must be at most " + max + " characters.");
        return s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
