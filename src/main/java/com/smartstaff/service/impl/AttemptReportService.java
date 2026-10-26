package com.smartstaff.service.impl;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.smartstaff.dto.response.AssessmentQuestionResponse;
import com.smartstaff.dto.response.AttemptReviewResponse;
import com.smartstaff.dto.response.AttemptReviewResponse.QuestionReview;
import com.smartstaff.entity.AssessmentAttempt;
import com.smartstaff.entity.Resume;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.ResumeRepository;
import com.smartstaff.service.ResultsService;
import com.smartstaff.service.ScorecardPdfService.ScorecardPdf;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** HR's full report for one submitted level-attempt, as a PDF: the candidate's
 *  name and resume first, then every question with the candidate's answer, the
 *  correct answer and the score. Long resumes and code are trimmed so a typical
 *  attempt fits in 3–4 pages. Contains the answer key, so it is HR-only (job
 *  access + signed download), never shown to candidates. */
@Service
public class AttemptReportService {

    static final int RESUME_CHARS = 2200;
    static final int CODE_LINES = 30;

    private static final Color INK = new Color(0x0f, 0x17, 0x2a);
    private static final Color MUTED = new Color(0x64, 0x74, 0x8b);
    private static final Color LINE = new Color(0xe2, 0xe8, 0xf0);
    private static final Color BRAND = new Color(0x43, 0x38, 0xca);
    private static final Color OK = new Color(0x05, 0x96, 0x69);
    private static final Color BAD = new Color(0xb9, 0x1c, 0x1c);
    private static final Color SOFT = new Color(0xf8, 0xfa, 0xfc);
    private static final Color CODE_BG = new Color(0xf1, 0xf5, 0xf9);

    private static final Font TITLE = font(22, Font.BOLD, INK);
    private static final Font SUB = font(11, Font.NORMAL, MUTED);
    private static final Font H2 = font(13, Font.BOLD, BRAND);
    private static final Font Q_HEAD = font(10, Font.BOLD, BRAND);
    private static final Font Q_TEXT = font(11, Font.BOLD, INK);
    private static final Font BODY = font(10, Font.NORMAL, INK);
    private static final Font LABEL = font(9, Font.BOLD, MUTED);
    private static final Font SMALL = font(8, Font.NORMAL, MUTED);
    private static final Font CODE = FontFactory.getFont(FontFactory.COURIER, 8, Font.NORMAL, INK);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    private final ResultsService results;
    private final AssessmentAttemptRepository attempts;
    private final ResumeRepository resumes;

    public AttemptReportService(ResultsService results, AssessmentAttemptRepository attempts, ResumeRepository resumes) {
        this.results = results;
        this.attempts = attempts;
        this.resumes = resumes;
    }

    @Transactional(readOnly = true)
    public ScorecardPdf report(UUID jobId, UUID attemptId) {
        AssessmentAttempt a = attempts.findById(attemptId)
                .filter(x -> x.getJob().getId().equals(jobId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attempt not found."));
        Resume resume = a.getResumeId() == null ? null : resumes.findById(a.getResumeId()).orElse(null);
        AttemptReviewResponse r = results.attempt(attemptId);
        String who = r.attempt().candidate_name() != null ? r.attempt().candidate_name() : r.attempt().candidate_email();
        String file = ("Report_" + (who == null ? "candidate" : who) + "_" + r.attempt().level())
                .replaceAll("[^A-Za-z0-9_-]+", "_") + ".pdf";
        return new ScorecardPdf(file, render(r, resume));
    }

    byte[] render(AttemptReviewResponse r, Resume resume) {
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            header(doc, r);
            resumeSection(doc, resume);
            doc.add(spaced(new Paragraph("Questions and answers", H2), 14, 6));
            List<QuestionReview> qs = r.questions() == null ? List.of() : r.questions();
            for (int i = 0; i < qs.size(); i++) question(doc, i + 1, qs.get(i));
            Paragraph foot = new Paragraph("Confidential — contains the answer key. For the hiring team only.", SMALL);
            foot.setSpacingBefore(12);
            doc.add(foot);
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the report", e);
        }
        return out.toByteArray();
    }

    // ── sections ────────────────────────────────────────────────────────

    private void header(Document doc, AttemptReviewResponse r) throws DocumentException {
        var s = r.attempt();
        doc.add(new Paragraph("Assessment Report", SUB));
        doc.add(new Paragraph(nz(s.candidate_name(), "Candidate"), TITLE));
        doc.add(spaced(new Paragraph(nz(s.candidate_email(), "") + "   ·   " + nz(s.job_title(), "") + "   ·   Level " + s.level(), SUB), 2, 10));

        PdfPTable t = new PdfPTable(5);
        t.setWidthPercentage(100);
        stat(t, "Overall", pct(s.overall_score()));
        stat(t, "Theory", pct(s.theory_score()));
        stat(t, "Hands-on", pct(s.hands_on_score()));
        stat(t, "Answered", s.answered() + " / " + s.num_questions());
        stat(t, "Recommendation", nz(pretty(s.recommendation()), "Pending"));
        doc.add(t);
        String when = s.submitted_at() == null ? "not submitted" : WHEN.format(s.submitted_at());
        doc.add(spaced(new Paragraph("Submitted " + when + (s.auto_submitted() ? " (time ran out)" : "")
                + (s.flags() == null || s.flags().isEmpty() ? "" : "   ·   Flags: " + String.join(", ", s.flags())), SMALL), 4, 4));
    }

    private void resumeSection(Document doc, Resume resume) throws DocumentException {
        doc.add(spaced(new Paragraph("Resume", H2), 12, 4));
        if (resume == null || resume.getExtractedText() == null || resume.getExtractedText().isBlank()) {
            doc.add(new Paragraph("No resume is linked to this candidate.", BODY));
            return;
        }
        doc.add(new Paragraph(resume.getFilename(), SMALL));
        String text = resume.getExtractedText().replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
        boolean cut = text.length() > RESUME_CHARS;
        if (cut) text = text.substring(0, RESUME_CHARS).trim() + " …";
        doc.add(box(new Paragraph(text, font(9, Font.NORMAL, INK)), SOFT));
        if (cut) doc.add(new Paragraph("Shortened — download the full resume from the Candidates page.", SMALL));
    }

    private void question(Document doc, int n, QuestionReview q) throws DocumentException {
        AssessmentQuestionResponse key = q.answer_key();
        // The candidate's snapshot only exists for answered questions; fall back to the answer key.
        Map<String, Object> view = new HashMap<>();
        if (key != null) {
            view.put("title", key.title());
            view.put("prompt", key.statement() != null ? key.statement() : key.question());
            view.put("options", key.options());
            view.put("starter_code", key.starter_code());
        }
        if (q.question() != null) q.question().forEach((k, v) -> { if (v != null) view.put(k, v); });
        BigDecimal score = q.sme_score() != null ? q.sme_score() : q.final_score() != null ? q.final_score() : q.auto_score();

        Paragraph head = new Paragraph();
        head.add(new Chunk("Q" + n + "  ·  " + label(q.type()) + "  ·  ", Q_HEAD));
        head.add(new Chunk(score == null ? "score pending" : "scored " + num(score) + " / " + q.points(),
                font(10, Font.BOLD, score == null ? MUTED : score.compareTo(BigDecimal.valueOf(q.points())) >= 0 ? OK
                        : score.signum() == 0 ? BAD : INK)));
        head.setSpacingBefore(12);
        head.setKeepTogether(true);
        doc.add(head);

        String title = str(view.get("title"));
        String prompt = str(view.get("prompt"));
        doc.add(spaced(new Paragraph((title.isEmpty() ? "" : title + "\n") + prompt, Q_TEXT), 3, 5));

        switch (q.type()) {
            case "MCQ", "MSQ" -> options(doc, view, key, q.selected_indices());
            case "CODE_WRITE", "CODE_DEBUG" -> {
                if ("CODE_DEBUG".equals(q.type())) code(doc, "Code given to the candidate", first(asMap(view.get("starter_code"))));
                code(doc, "Candidate's code" + (q.language() == null ? "" : " (" + q.language() + ")"), nz(q.code(), ""));
                Map<String, String> ref = key == null ? null : "CODE_DEBUG".equals(q.type()) && key.fixed_code() != null
                        ? key.fixed_code() : key.reference_solution();
                code(doc, "Reference solution", ref == null ? "" : nz(ref.get(q.language()), first(ref)));
            }
            case "CODE_OUTPUT" -> {
                code(doc, "Program", first(asMap(view.get("starter_code"))));
                answer(doc, "Candidate's output", q.text_answer());
                String expected = key == null || key.tests() == null || key.tests().isEmpty() ? null : key.tests().get(0).expected_output();
                answer(doc, "Correct output", expected);
            }
            default -> {   // SCENARIO, LOGIC
                answer(doc, "Candidate's answer", q.text_answer());
                if (key != null) {
                    answer(doc, "Expected answer", key.model_answer());
                    if (key.key_points() != null && !key.key_points().isEmpty()) {
                        answer(doc, "Key points", "• " + String.join("\n• ", key.key_points()));
                    }
                }
            }
        }
        if (key != null && key.explanation() != null && !key.explanation().isBlank()) {
            doc.add(spaced(new Paragraph("Why: " + key.explanation(), font(9, Font.ITALIC, MUTED)), 3, 0));
        }
        if (q.sme_reason() != null && !q.sme_reason().isBlank()) {
            doc.add(new Paragraph("Reviewer note: " + q.sme_reason(), font(9, Font.ITALIC, MUTED)));
        }
    }

    private void options(Document doc, Map<String, Object> view, AssessmentQuestionResponse key, List<Integer> selected)
            throws DocumentException {
        List<?> options = view.get("options") instanceof List<?> l ? l : List.of();
        Set<Integer> correct = new HashSet<>();   // MCQ keys may carry only correct_index
        if (key != null && key.correct_indices() != null) correct.addAll(key.correct_indices());
        if (key != null && key.correct_index() != null) correct.add(key.correct_index());
        Set<Integer> chosen = selected == null ? Set.of() : new HashSet<>(selected);
        PdfPTable t = new PdfPTable(new float[]{0.6f, 8f, 2.4f});
        t.setWidthPercentage(100);
        for (int i = 0; i < options.size(); i++) {
            boolean c = correct.contains(i), s = chosen.contains(i);
            Color bg = c ? new Color(0xec, 0xfd, 0xf5) : s ? new Color(0xfe, 0xf2, 0xf2) : Color.WHITE;
            t.addCell(cell(String.valueOf((char) ('A' + i)), font(9, Font.BOLD, BRAND), bg));
            t.addCell(cell(String.valueOf(options.get(i)), BODY, bg));
            String tag = c && s ? "Correct · chosen" : c ? "Correct answer" : s ? "Chosen (wrong)" : "";
            t.addCell(cell(tag, font(8, Font.BOLD, c ? OK : BAD), bg));
        }
        doc.add(t);
        if (chosen.isEmpty()) doc.add(new Paragraph("Not answered.", font(9, Font.ITALIC, BAD)));
    }

    private void answer(Document doc, String label, String text) throws DocumentException {
        doc.add(spaced(new Paragraph(label, LABEL), 4, 1));
        doc.add(box(new Paragraph(text == null || text.isBlank() ? "(no answer)" : text, BODY), SOFT));
    }

    private void code(Document doc, String label, String code) throws DocumentException {
        doc.add(spaced(new Paragraph(label, LABEL), 4, 1));
        String[] lines = (code == null || code.isBlank() ? "(empty)" : code.stripTrailing()).split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, CODE_LINES); i++) sb.append(lines[i]).append('\n');
        if (lines.length > CODE_LINES) sb.append("… ").append(lines.length - CODE_LINES).append(" more lines");
        doc.add(box(new Paragraph(sb.toString(), CODE), CODE_BG));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static PdfPTable box(Paragraph p, Color bg) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.addElement(p);
        c.setBackgroundColor(bg);
        c.setBorderColor(LINE);
        c.setPadding(7);
        t.addCell(c);
        return t;
    }

    private static PdfPCell cell(String text, Font f, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(bg);
        c.setBorderColor(LINE);
        c.setPadding(5);
        return c;
    }

    private static void stat(PdfPTable t, String label, String value) {
        PdfPCell c = new PdfPCell();
        c.addElement(new Paragraph(label, SMALL));
        c.addElement(new Paragraph(value, font(13, Font.BOLD, INK)));
        c.setBorderColor(LINE);
        c.setBackgroundColor(SOFT);
        c.setPadding(7);
        t.addCell(c);
    }

    private static Paragraph spaced(Paragraph p, float before, float after) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static Font font(float size, int style, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }

    private static String label(String type) {
        return switch (type) {
            case "MCQ" -> "Multiple choice";
            case "MSQ" -> "Multiple select";
            case "CODE_WRITE" -> "Coding";
            case "CODE_DEBUG" -> "Debugging";
            case "CODE_OUTPUT" -> "Predict output";
            case "SCENARIO" -> "Scenario";
            case "LOGIC" -> "Logic";
            default -> type;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    private static String first(Map<String, String> code) {
        return code == null || code.isEmpty() ? "" : code.values().iterator().next();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String pretty(String s) {
        return s == null ? null : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String pct(BigDecimal v) {
        return v == null ? "—" : num(v) + "%";
    }

    private static String num(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
