package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.*;

/** F14: GET /api/download_report as .xlsx (replaces the CSV). Sheet
 *  "Candidates" — one row per screened candidate with their latest scored
 *  assessment; sheet "Assessments" — one row per level-attempt. Scoped like
 *  the job list: admin = all jobs, employee = own jobs. Cells are written as
 *  strings/numbers, never formulas, so a value like "=cmd()" stays inert. */
@Service
public class ReportXlsxService {

    static final List<String> CANDIDATE_HEADERS = List.of("JD Number", "Role", "Candidate", "Email", "Phone",
            "Years Experience", "Fit Score", "Matched Skills", "Missing Skills",
            "Assessment Level", "Theory", "Hands-on", "Overall", "Recommendation", "Result Status");
    static final List<String> ASSESSMENT_HEADERS = List.of("JD Number", "Role", "Candidate", "Email", "Level", "Version",
            "Status", "Submitted", "Theory", "Theory Band", "Hands-on", "Hands-on Band", "Overall", "Recommendation",
            "Result Status", "Flags");

    private final JobRepository jobs;
    private final CandidateRepository candidates;
    private final AssessmentAttemptRepository attempts;

    public ReportXlsxService(JobRepository jobs, CandidateRepository candidates, AssessmentAttemptRepository attempts) {
        this.jobs = jobs;
        this.candidates = candidates;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public byte[] report(User requester) {
        List<Job> visible = requester.getRole() == Role.ADMIN
                ? jobs.findAllByOrderByCreatedAtDesc()
                : jobs.findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(requester.publicId());
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            Sheet cand = sheet(wb, "Candidates", CANDIDATE_HEADERS, bold);
            Sheet asmt = sheet(wb, "Assessments", ASSESSMENT_HEADERS, bold);

            for (Job job : visible) {
                List<AssessmentAttempt> jobAttempts = attempts.findByJobIdOrderByStartedAtDesc(job.getId());
                for (AssessmentAttempt a : jobAttempts) {
                    row(asmt, job.jdNumberDisplay(), job.getTitle(), a.getCandidateName(), a.getCandidateEmail(),
                            a.getLevel(), a.getVersion(), a.getStatus().name(),
                            a.getSubmittedAt() == null ? null : a.getSubmittedAt().toString(),
                            a.getTheoryScore(), a.getTheoryBand(), a.getHandsOnScore(), a.getHandsOnBand(),
                            a.getOverallScore(), a.getRecommendation(), resultStatus(a.getStatus()),
                            String.join(", ", a.getFlags()));
                }
                for (Candidate c : candidates.findByJobIdOrderByFitScoreDesc(job.getId())) {
                    AssessmentAttempt latest = latestScored(jobAttempts, c.getEmail());
                    row(cand, job.jdNumberDisplay(), job.getTitle(), c.getCandidateName(), c.getEmail(), c.getPhone(),
                            c.getYearsExperience(), c.getFitScore(),
                            String.join("; ", c.getMatchedSkills()), String.join("; ", c.getMissingSkills()),
                            latest == null ? null : latest.getLevel(),
                            latest == null ? null : latest.getTheoryScore(),
                            latest == null ? null : latest.getHandsOnScore(),
                            latest == null ? null : latest.getOverallScore(),
                            latest == null ? null : latest.getRecommendation(),
                            latest == null ? null : resultStatus(latest.getStatus()));
                }
            }
            for (Sheet s : List.of(cand, asmt)) {
                for (int i = 0; i < s.getRow(0).getLastCellNum(); i++) s.setColumnWidth(i, 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Newest attempt with a result for that email (list is newest first). */
    private static AssessmentAttempt latestScored(List<AssessmentAttempt> all, String email) {
        if (email == null) return null;
        for (AssessmentAttempt a : all) {
            if (email.equalsIgnoreCase(a.getCandidateEmail()) && resultStatus(a.getStatus()) != null) return a;
        }
        return null;
    }

    private static String resultStatus(AttemptStatus s) {
        return switch (s) {
            case VALIDATED -> "VALIDATED";
            case EVALUATED, AWAITING_REVIEW -> "PROVISIONAL";
            default -> null;
        };
    }

    private static Sheet sheet(Workbook wb, String name, List<String> headers, CellStyle bold) {
        Sheet s = wb.createSheet(name);
        Row r = s.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = r.createCell(i);
            c.setCellValue(headers.get(i));
            c.setCellStyle(bold);
        }
        s.createFreezePane(0, 1);
        return s;
    }

    private static void row(Sheet s, Object... values) {
        Row r = s.createRow(s.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) continue;
            Cell c = r.createCell(i);
            if (v instanceof BigDecimal d) c.setCellValue(d.doubleValue());
            else if (v instanceof Number n) c.setCellValue(n.doubleValue());
            else c.setCellValue(v.toString());
        }
    }
}
