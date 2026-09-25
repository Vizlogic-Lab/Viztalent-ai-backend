package com.smartstaff.service.impl;

import com.smartstaff.dto.response.CandidateRowResponse;
import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Resume;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.CandidateMapper;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.ResumeRepository;
import com.smartstaff.service.ScreeningService;
import com.smartstaff.util.ExperienceParser;
import com.smartstaff.util.SkillDictionary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScreeningServiceImpl implements ScreeningService {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // \x20 (literal space) only, NOT \s — \s matches newlines too, which let
    // this swallow into the next line (e.g. "9876543210\n5 years..." matched
    // as one phone number, capturing a stray leading digit from the next
    // sentence). Phone numbers are written on a single line.
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d\\-()\\x20]{8,14}\\d)");
    private static final Pattern LOOKS_LIKE_NAME = Pattern.compile("^[A-Z][a-zA-Z.'-]*(\\s+[A-Z][a-zA-Z.'-]*){1,3}$");
    // Must-have skills are worth up to this many points; nice-to-have fills the rest.
    private static final int MUST_HAVE_WEIGHT = 85;
    private static final int NICE_TO_HAVE_WEIGHT = 15;
    private static final int EXPERIENCE_BONUS = 5;

    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final CandidateMapper candidateMapper;
    private final SkillDictionary skillDictionary;
    private final ExperienceParser experienceParser;

    public ScreeningServiceImpl(
            JobRepository jobRepository,
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            CandidateMapper candidateMapper,
            SkillDictionary skillDictionary,
            ExperienceParser experienceParser
    ) {
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.candidateMapper = candidateMapper;
        this.skillDictionary = skillDictionary;
        this.experienceParser = experienceParser;
    }

    @Override
    @Transactional
    public RunScreeningResponse runScreening(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        List<Resume> resumes = resumeRepository.findByJobIdOrderByUploadedAtDesc(jobId);
        if (resumes.isEmpty()) {
            return new RunScreeningResponse(
                    "There are no resumes uploaded for this job yet — upload some first.", List.of());
        }

        // Re-running screening recomputes everyone from scratch, so edits to
        // the JD or newly-added resumes are always reflected.
        candidateRepository.deleteByJobId(jobId);

        // effectiveRequired: fall back to every skill when the JD has no
        // must-have skills at all (e.g. a skills-only JD where everything
        // was phrased as "nice to have") — otherwise Total_Required would be
        // a meaningless 0 for every candidate.
        List<String> required = job.getMustHaveSkills().isEmpty() ? job.allSkills() : job.getMustHaveSkills();
        List<String> niceToHave = job.getMustHaveSkills().isEmpty() ? List.of() : job.getNiceToHaveSkills();

        List<Candidate> scored = new ArrayList<>();
        for (Resume resume : resumes) {
            scored.add(scoreCandidate(job, resume, required, niceToHave));
        }
        candidateRepository.saveAll(scored);

        // Highest score first, matching the frontend's own sort of the table.
        scored.sort(Comparator.comparingInt(Candidate::getFitScore).reversed());
        List<CandidateRowResponse> rows = scored.stream().map(candidateMapper::toRowResponse).toList();

        int shortlisted = (int) scored.stream().filter(c -> c.getFitScore() >= 50).count();
        String reply = "Screened " + scored.size() + " candidate" + (scored.size() == 1 ? "" : "s")
                + " — " + shortlisted + " scored 50 or above.";

        return new RunScreeningResponse(reply, rows);
    }

    @Override
    public ProgressResponse progress() {
        return ProgressResponse.IDLE;
    }

    @Override
    public byte[] downloadReportCsv(User requester) {
        // This endpoint is hit via a plain <a href target="_blank"> link in
        // the frontend (Dashboard/Candidates/Settings), never through axios,
        // so it carries no bearer token and `requester` is null — see the
        // permitAll note in SecurityConfig. With no identity to scope by,
        // export everything rather than guessing or crashing.
        List<Job> jobs = (requester == null || requester.getRole() == Role.ADMIN)
                ? jobRepository.findAllByOrderByCreatedAtDesc()
                : jobRepository.findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(requester.publicId());

        StringBuilder csv = new StringBuilder();
        csv.append("JD Number,Role,Candidate,Email,Phone,Years Experience,Fit Score,Matched Skills,Missing Skills\n");
        for (Job job : jobs) {
            for (Candidate c : candidateRepository.findByJobIdOrderByFitScoreDesc(job.getId())) {
                csv.append(csvCell(job.jdNumberDisplay())).append(',')
                        .append(csvCell(job.getTitle())).append(',')
                        .append(csvCell(c.getCandidateName())).append(',')
                        .append(csvCell(c.getEmail())).append(',')
                        .append(csvCell(c.getPhone())).append(',')
                        .append(c.getYearsExperience()).append(',')
                        .append(c.getFitScore()).append(',')
                        .append(csvCell(String.join("; ", c.getMatchedSkills()))).append(',')
                        .append(csvCell(String.join("; ", c.getMissingSkills()))).append('\n');
            }
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── scoring ──────────────────────────────────────────────────────────

    private Candidate scoreCandidate(Job job, Resume resume, List<String> required, List<String> niceToHave) {
        String text = resume.getExtractedText() == null ? "" : resume.getExtractedText();

        List<String> resumeSkills = skillDictionary.extract(text);
        List<String> matchedRequired = intersect(required, resumeSkills);
        List<String> matchedNice = intersect(niceToHave, resumeSkills);
        List<String> missingRequired = required.stream()
                .filter(s -> matchedRequired.stream().noneMatch(s::equalsIgnoreCase))
                .toList();

        double requiredScore = required.isEmpty() ? MUST_HAVE_WEIGHT
                : (double) matchedRequired.size() / required.size() * MUST_HAVE_WEIGHT;
        double niceScore = niceToHave.isEmpty() ? 0
                : (double) matchedNice.size() / niceToHave.size() * NICE_TO_HAVE_WEIGHT;

        int years = experienceParser.extractYearsOfExperience(text);
        int experienceBonus = 0;
        if (job.getExperienceMinYears() != null) {
            experienceBonus = years >= job.getExperienceMinYears() ? EXPERIENCE_BONUS : -EXPERIENCE_BONUS;
        }

        int fitScore = clamp((int) Math.round(requiredScore + niceScore) + experienceBonus, 0, 100);

        List<String> allMatched = new ArrayList<>(matchedRequired);
        allMatched.addAll(matchedNice);

        String breakdown = String.format(
                "Skills match %d/%d required (%.0f%%)%s · %d yrs experience · weighted fit %d/100",
                matchedRequired.size(), required.size(),
                required.isEmpty() ? 100.0 : (double) matchedRequired.size() / required.size() * 100,
                matchedNice.isEmpty() ? "" : String.format(" + %d/%d nice-to-have", matchedNice.size(), niceToHave.size()),
                years, fitScore
        );

        Candidate candidate = new Candidate();
        candidate.setJob(job);
        candidate.setResume(resume);
        candidate.setCandidateName(candidateName(text, resume.getFilename()));
        candidate.setEmail(firstMatch(EMAIL, text));
        candidate.setPhone(firstMatch(PHONE, text));
        candidate.setYearsExperience(years);
        candidate.setFitScore(fitScore);
        candidate.setMatchedSkills(allMatched);
        candidate.setMissingSkills(missingRequired);
        candidate.setMatchedRequiredCount(matchedRequired.size());
        candidate.setTotalRequiredCount(required.size());
        candidate.setScoreBreakdown(breakdown);
        return candidate;
    }

    private static List<String> intersect(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (b.stream().anyMatch(s::equalsIgnoreCase)) out.add(s);
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    /** Prefers the first non-blank line of the resume when it *looks* like a
     *  plain name (2-4 capitalized words, no digits/@/punctuation-heavy
     *  content) — resumes conventionally lead with the candidate's name.
     *  Falls back to a cleaned-up filename otherwise. Deterministic
     *  best-effort — there's no NLP name extraction here. */
    private static String candidateName(String text, String filename) {
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                String candidate = line.strip();
                if (candidate.isEmpty()) continue;
                // Strip a leading title like "Name -" or "Name:" before checking shape.
                String core = candidate.split("[-:|]", 2)[0].strip();
                if (LOOKS_LIKE_NAME.matcher(core).matches()) return core;
                break; // only the very first non-blank line counts as a name candidate
            }
        }
        return candidateNameFromFilename(filename);
    }

    /** Same filename-cleanup approach as JobServiceImpl.titleFromFilename,
     *  plus stripping "resume"/"cv" so "Jane_Doe_Resume.pdf" reads as
     *  "Jane Doe" instead of "Jane Doe Resume". Deterministic best-effort —
     *  there's no NLP name extraction here. */
    private static String candidateNameFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "Unknown candidate";
        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        String spaced = base.replaceAll("[_\\-]+", " ").strip();
        spaced = spaced.replaceAll("(?i)\\b(resume|cv)\\b", "").trim().replaceAll("\\s+", " ");
        if (spaced.isEmpty()) return "Unknown candidate";
        String[] words = spaced.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.length() > 1 ? w.substring(1) : "");
        }
        return sb.toString();
    }

    private static String csvCell(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
