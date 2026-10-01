package com.smartstaff.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** F14: renders one attempt's result as a shareable PDF — header, Theory and
 *  Hands-on with bands, competencies, evidence lines, recommendation, a
 *  Provisional/Validated stamp (with the SME and comment) and an integrity
 *  summary. Pure formatting from a Card the caller builds from stored results;
 *  the Card carries no questions, tests or reference solutions, so the PDF
 *  can't leak them. */
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
    private static final Font FOOT = font(8, Font.NORMAL, MUTED);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    /** A rendered scorecard PDF and the filename to serve it as. */
    public record ScorecardPdf(String filename, byte[] bytes) {}

    /** Everything the PDF shows. resultStatus is PROVISIONAL or VALIDATED. */
    public record Card(String candidateName, String candidateEmail, String jobTitle, String jdNumber, String level,
                       int version, Instant submittedAt, BigDecimal theory, BigDecimal handsOn, BigDecimal overall,
                       String theoryBand, String handsOnBand, String recommendation,
                       Map<String, BigDecimal> competencies, List<String> evidenceLines, String resultStatus,
                       String reviewerName, String smeComment, Instant signedOffAt, String validationReason,
                       List<String> flags, Map<String, Long> integrityEvents, boolean autoSubmitted) {}

    public byte[] render(Card card) {
        Document doc = new Document(PageSize.A4, 42, 42, 44, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(paragraph("Assessment Scorecard", H1, 2));
            doc.add(paragraph(nz(card.jobTitle()), font(12, Font.NORMAL, MUTED), 12));
            doc.add(headerGrid(card));
            doc.add(resultBanner(card));
            doc.add(stamp(card));

            if (card.competencies() != null && !card.competencies().isEmpty()) {
                doc.add(paragraph("Competencies", H2, 6));
                doc.add(competencyTable(card.competencies()));
            }
            if (card.evidenceLines() != null && !card.evidenceLines().isEmpty()) {
                doc.add(paragraph("Evidence", H2, 4));
                com.lowagie.text.List list = new com.lowagie.text.List(false, 10);
                for (String line : card.evidenceLines()) list.add(new ListItem(line, TD));
                doc.add(list);
            }
            doc.add(paragraph("Integrity", H2, 4));
            doc.add(paragraph(integrity(card), TD, 4));

            doc.add(footer());
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the scorecard PDF", e);
        }
    }

    // ── header ──────────────────────────────────────────────────────────

    private PdfPTable headerGrid(Card card) {
        PdfPTable grid = new PdfPTable(4);
        grid.setWidthPercentage(100);
        grid.setSpacingAfter(14);
        cellPair(grid, "Candidate", card.candidateName() != null && !card.candidateName().isBlank()
                ? card.candidateName() : card.candidateEmail());
        cellPair(grid, "Email", card.candidateEmail());
        cellPair(grid, "Role", card.jobTitle());
        cellPair(grid, "JD number", card.jdNumber());
        cellPair(grid, "Level", card.level());
        cellPair(grid, "Version", "v" + card.version());
        cellPair(grid, "Submitted", card.submittedAt() == null ? null : WHEN.format(card.submittedAt()));
        cellPair(grid, "Overall", card.overall() == null ? null : plain(card.overall()));
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

    // ── scores + recommendation ─────────────────────────────────────────

    private PdfPTable resultBanner(Card card) {
        PdfPTable banner = new PdfPTable(new float[] {1f, 1f, 1.3f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(8);
        banner.addCell(scoreCell("Theory", card.theory(), card.theoryBand()));
        banner.addCell(scoreCell("Hands-on", card.handsOn(), card.handsOnBand()));

        String rec = card.recommendation() == null ? "—" : card.recommendation().replace('_', ' ');
        Color accent = "PROCEED".equals(card.recommendation()) ? PASS
                : "NOT_RECOMMENDED".equals(card.recommendation()) ? FAIL : REVIEW;
        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(accent);
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(12);
        right.addElement(new Paragraph("Recommendation", font(9, Font.NORMAL, Color.WHITE)));
        right.addElement(new Paragraph(rec, font(16, Font.BOLD, Color.WHITE)));
        banner.addCell(right);
        return banner;
    }

    private PdfPCell scoreCell(String label, BigDecimal score, String band) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(HEAD_BG);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(12);
        cell.addElement(new Paragraph(label, LABEL));
        cell.addElement(new Paragraph(score == null ? "n/a" : plain(score) + " / 100", font(16, Font.BOLD, INK)));
        cell.addElement(new Paragraph(band == null ? "" : band.replace('_', ' '), font(9, Font.BOLD, MUTED)));
        return cell;
    }

    private Paragraph stamp(Card card) {
        boolean validated = "VALIDATED".equals(card.resultStatus());
        StringBuilder sb = new StringBuilder(validated ? "VALIDATED" : "PROVISIONAL");
        if (validated && card.reviewerName() != null) {
            sb.append(" — signed off by ").append(card.reviewerName());
            if (card.signedOffAt() != null) sb.append(" on ").append(WHEN.format(card.signedOffAt()));
        } else if (validated && card.validationReason() != null) {
            sb.append(" — without SME review (").append(card.validationReason()).append(')');
        } else if (!validated) {
            sb.append(" — automatic result, not yet validated by a subject-matter expert");
        }
        Paragraph p = new Paragraph(sb.toString(), font(10, Font.BOLD, validated ? PASS : REVIEW));
        if (card.smeComment() != null && !card.smeComment().isBlank()) {
            p.add(new Chunk("\nSME comment: " + card.smeComment(), font(9, Font.ITALIC, INK)));
        }
        p.setSpacingAfter(14);
        return p;
    }

    private PdfPTable competencyTable(Map<String, BigDecimal> competencies) {
        PdfPTable table = new PdfPTable(new float[] {3f, 1f});
        table.setWidthPercentage(70);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingAfter(12);
        th(table, "Competency");
        th(table, "Score");
        competencies.forEach((name, pct) -> {
            td(table, name.replace('_', ' '));
            td(table, pct == null ? "—" : plain(pct) + "%");
        });
        return table;
    }

    private static String integrity(Card card) {
        StringBuilder sb = new StringBuilder();
        Map<String, Long> ev = card.integrityEvents() == null ? Map.of() : card.integrityEvents();
        sb.append("Tab switches: ").append(ev.getOrDefault("TAB_SWITCH", 0L))
                .append(" · Pastes: ").append(ev.getOrDefault("PASTE", 0L))
                .append(" · Focus lost: ").append(ev.getOrDefault("FOCUS_LOST", 0L))
                .append(" · Full-screen exits: ").append(ev.getOrDefault("FULLSCREEN_EXIT", 0L));
        if (card.autoSubmitted()) sb.append(" · Auto-submitted when time ran out");
        if (card.flags() != null && !card.flags().isEmpty()) {
            sb.append("\nFlags: ").append(String.join(", ", card.flags()));
        }
        return sb.toString();
    }

    // ── low-level helpers ───────────────────────────────────────────────

    private void th(PdfPTable table, String label) {
        PdfPCell cell = new PdfPCell(new Phrase(label, TH));
        cell.setBackgroundColor(HEAD_BG);
        cell.setBorderColor(LINE);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void td(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, TD));
        cell.setBorderColor(LINE);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private Paragraph footer() {
        Paragraph p = new Paragraph("Generated by SmartStaff · " + WHEN.format(Instant.now())
                + " · Scores are computed from stored results; questions and reference solutions are not included.", FOOT);
        p.setSpacingBefore(16);
        return p;
    }

    private static Paragraph paragraph(String text, Font font, float spacingAfter) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingAfter(spacingAfter);
        return p;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static Font font(float size, int style, Color color) {
        Font f = new Font(Font.HELVETICA, size, style);
        f.setColor(color);
        return f;
    }
}
