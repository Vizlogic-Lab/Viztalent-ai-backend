package com.smartstaff.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.smartstaff.dto.response.ScorecardResponse;
import com.smartstaff.entity.QuestionScoreBreakdown;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders a scored scorecard to a one-or-two-page PDF: a header with the
 *  candidate and result, then a per-question table. Pure formatting — no data
 *  access — so it can be unit-tested on a plain ScorecardResponse. */
@Service
public class ScorecardPdfService {

    private static final Color INK = new Color(0x1f, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6b, 0x72, 0x80);
    private static final Color LINE = new Color(0xe5, 0xe7, 0xeb);
    private static final Color PASS = new Color(0x0f, 0x9d, 0x58);
    private static final Color FAIL = new Color(0xd9, 0x30, 0x25);
    private static final Color REVIEW = new Color(0xb4, 0x53, 0x09);
    private static final Color HEAD_BG = new Color(0xf3, 0xf4, 0xf6);

    private static final Font H1 = font(20, Font.BOLD, INK);
    private static final Font H2 = font(12, Font.BOLD, INK);
    private static final Font LABEL = font(9, Font.NORMAL, MUTED);
    private static final Font VALUE = font(10, Font.NORMAL, INK);
    private static final Font TH = font(9, Font.BOLD, INK);
    private static final Font TD = font(9, Font.NORMAL, INK);
    private static final Font TD_MUTED = font(8, Font.NORMAL, MUTED);
    private static final Font FOOT = font(8, Font.NORMAL, MUTED);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    /** A rendered scorecard PDF and the filename to serve it as. */
    public record ScorecardPdf(String filename, byte[] bytes) {}

    public byte[] render(String jobTitle, ScorecardResponse card) {
        Document doc = new Document(PageSize.A4, 42, 42, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(paragraph("Assessment Scorecard", H1, 2));
            doc.add(paragraph(jobTitle == null ? "" : jobTitle, font(12, Font.NORMAL, MUTED), 12));

            doc.add(headerGrid(card));
            doc.add(resultBanner(card));
            if (card.needs_review()) {
                doc.add(reviewNote(card));
            }

            doc.add(paragraph("Question breakdown", H2, 6));
            doc.add(questionTable(card));

            doc.add(footer());
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the scorecard PDF", e);
        }
    }

    // ── header (candidate + meta) ───────────────────────────────────────

    private PdfPTable headerGrid(ScorecardResponse card) {
        PdfPTable grid = new PdfPTable(4);
        grid.setWidthPercentage(100);
        grid.setSpacingAfter(14);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        grid.getDefaultCell().setPaddingBottom(6);

        cellPair(grid, "Candidate", value(card.candidate_name(), card.candidate_email()));
        cellPair(grid, "Email", card.candidate_email());
        cellPair(grid, "Levels", card.levels() == null ? "" : String.join(", ", card.levels()));
        cellPair(grid, "Version", card.version() == null ? "—" : "v" + card.version());
        cellPair(grid, "Submitted", card.submitted_at() == null ? "—" : WHEN.format(card.submitted_at()));
        cellPair(grid, "Scored", card.scored_at() == null ? "—" : WHEN.format(card.scored_at()));
        cellPair(grid, "Pass mark", card.pass_threshold() + "%");
        cellPair(grid, "Status", card.status());
        return grid;
    }

    private void cellPair(PdfPTable grid, String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "\n", LABEL));
        p.add(new Chunk(value == null || value.isBlank() ? "—" : value, VALUE));
        PdfPCell cell = new PdfPCell(p);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8);
        grid.addCell(cell);
    }

    // ── result banner ───────────────────────────────────────────────────

    private PdfPTable resultBanner(ScorecardResponse card) {
        boolean provisional = card.needs_review();
        Color accent = card.passed() ? PASS : FAIL;
        String verdict = card.passed() ? "PASS" : "FAIL";
        if (provisional) verdict += " (provisional)";

        PdfPTable banner = new PdfPTable(new float[] {1.4f, 3f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(14);

        PdfPCell left = new PdfPCell();
        left.setBackgroundColor(accent);
        left.setBorder(Rectangle.NO_BORDER);
        left.setPadding(12);
        left.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph pct = new Paragraph(plain(card.percent()) + "%", font(26, Font.BOLD, Color.WHITE));
        pct.setAlignment(Element.ALIGN_CENTER);
        left.addElement(pct);
        Paragraph verdictP = new Paragraph(verdict, font(12, Font.BOLD, Color.WHITE));
        verdictP.setAlignment(Element.ALIGN_CENTER);
        left.addElement(verdictP);
        banner.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(HEAD_BG);
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(12);
        right.addElement(new Paragraph("Score", LABEL));
        right.addElement(new Paragraph(plain(card.total_score()) + " / " + plain(card.max_score()) + " points",
                font(16, Font.BOLD, INK)));
        if (provisional) {
            right.addElement(new Paragraph(plain(card.review_points())
                    + " points await manual review — this result is not final.", font(9, Font.NORMAL, REVIEW)));
        }
        banner.addCell(right);
        return banner;
    }

    private Paragraph reviewNote(ScorecardResponse card) {
        Paragraph p = new Paragraph("Some answers could not be graded automatically (the code runner or the AI grader "
                + "was unavailable). " + plain(card.review_points()) + " points are marked \"needs review\" below and "
                + "must be graded by a person before the result is final.", font(9, Font.ITALIC, REVIEW));
        p.setSpacingAfter(10);
        return p;
    }

    // ── question table ──────────────────────────────────────────────────

    private PdfPTable questionTable(ScorecardResponse card) {
        PdfPTable table = new PdfPTable(new float[] {0.7f, 1.4f, 1.6f, 1.1f, 1.0f, 3.3f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        header(table, "Q", "Type", "Skill", "Tests", "Score", "Detail");

        List<ScorecardResponse.QuestionScoreView> questions =
                card.questions() == null ? List.of() : card.questions();
        boolean alt = false;
        for (ScorecardResponse.QuestionScoreView q : questions) {
            Color bg = alt ? new Color(0xfa, 0xfa, 0xfa) : Color.WHITE;
            alt = !alt;
            td(table, bg, q.level() + "·" + (q.seq() + 1), TD);
            td(table, bg, q.type(), TD);
            td(table, bg, q.skill() == null ? "—" : q.skill(), TD);
            td(table, bg, q.tests_total() == null ? "—" : q.tests_passed() + "/" + q.tests_total(), TD);
            td(table, bg, plain(q.score()) + "/" + q.max_points(), q.needs_review() ? font(9, Font.BOLD, REVIEW) : TD);
            td(table, bg, detail(q), TD_MUTED);
        }
        return table;
    }

    private static String detail(ScorecardResponse.QuestionScoreView q) {
        StringBuilder sb = new StringBuilder();
        if (!q.answered()) sb.append("Not answered. ");
        if (q.needs_review()) sb.append("[needs review] ");
        if (q.detail() != null) sb.append(q.detail());
        if (q.breakdown() != null && !q.breakdown().isEmpty()) {
            StringBuilder parts = new StringBuilder();
            for (QuestionScoreBreakdown b : q.breakdown()) {
                if (parts.length() > 0) parts.append(", ");
                parts.append(b.label()).append(' ').append(trim(b.awarded())).append('/').append(trim(b.weight()));
            }
            sb.append("  (").append(parts).append(')');
        }
        return sb.toString().trim();
    }

    // ── low-level helpers ───────────────────────────────────────────────

    private void header(PdfPTable table, String... labels) {
        for (String label : labels) {
            PdfPCell cell = new PdfPCell(new Phrase(label, TH));
            cell.setBackgroundColor(HEAD_BG);
            cell.setBorderColor(LINE);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private void td(PdfPTable table, Color bg, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(LINE);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private Paragraph footer() {
        Paragraph p = new Paragraph("Generated by SmartStaff · " + WHEN.format(java.time.Instant.now())
                + " · Automatic grading; the code runner and AI grader are audited per question above.", FOOT);
        p.setSpacingBefore(16);
        return p;
    }

    private static Paragraph paragraph(String text, Font font, float spacingAfter) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingAfter(spacingAfter);
        return p;
    }

    private static String value(String name, String email) {
        if (name != null && !name.isBlank()) return name;
        return email == null ? "—" : email;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static Font font(float size, int style, Color color) {
        Font f = new Font(Font.HELVETICA, size, style);
        f.setColor(color);
        return f;
    }
}
