package com.smartstaff.service.impl;

import com.smartstaff.dto.request.JdSkillsOnlyRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.Resume;
import com.smartstaff.entity.Role;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.CandidateMapper;
import com.smartstaff.mapper.JobMapper;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.ResumeRepository;
import com.smartstaff.service.JobService;
import com.smartstaff.util.ExperienceParser;
import com.smartstaff.util.FileStorageService;
import com.smartstaff.util.SkillDictionary;
import com.smartstaff.util.TextExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JobServiceImpl implements JobService {

    private final JobRepository jobRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final JobMapper jobMapper;
    private final CandidateMapper candidateMapper;
    private final FileStorageService fileStorageService;
    private final TextExtractor textExtractor;
    private final SkillDictionary skillDictionary;
    private final ExperienceParser experienceParser;
    private final int maxRetainedJobs;

    public JobServiceImpl(
            JobRepository jobRepository,
            ResumeRepository resumeRepository,
            CandidateRepository candidateRepository,
            JobMapper jobMapper,
            CandidateMapper candidateMapper,
            FileStorageService fileStorageService,
            TextExtractor textExtractor,
            SkillDictionary skillDictionary,
            ExperienceParser experienceParser,
            @Value("${app.jobs.max-retained}") int maxRetainedJobs
    ) {
        this.jobRepository = jobRepository;
        this.resumeRepository = resumeRepository;
        this.candidateRepository = candidateRepository;
        this.jobMapper = jobMapper;
        this.candidateMapper = candidateMapper;
        this.fileStorageService = fileStorageService;
        this.textExtractor = textExtractor;
        this.skillDictionary = skillDictionary;
        this.experienceParser = experienceParser;
        this.maxRetainedJobs = maxRetainedJobs;
    }

    @Override
    @Transactional
    public JdUploadResponse uploadJd(MultipartFile file, boolean force, User owner) {
        byte[] bytes = readBytes(file);
        String contentHash = FileStorageService.sha256Hex(bytes);

        if (!force) {
            var existing = jobRepository.findByOwnerIdIgnoreCaseAndContentHash(owner.publicId(), contentHash);
            if (existing.isPresent()) {
                Job job = existing.get();
                return new JdUploadResponse(
                        "duplicate",
                        "This job description already exists (" + job.jdNumberDisplay() + " — " + job.getTitle() + "). Upload again as a new job?",
                        null, null, null, null, null,
                        job.getId().toString(), job.getTitle(), job.jdNumberDisplay(), false
                );
            }
        }

        String text = extractTextBestEffort(bytes, file.getOriginalFilename());
        var classified = skillDictionary.classify(text);
        var experience = experienceParser.extractRange(text);

        String storedPath = storeFile(file, "jobs/" + UUID.randomUUID());

        Job job = new Job();
        job.setTitle(titleFromFilename(file.getOriginalFilename()));
        job.setOriginalFilename(file.getOriginalFilename());
        job.setFilePath(storedPath);
        job.setJdText(text);
        job.setContentHash(contentHash);
        job.setMustHaveSkills(classified.mustHave());
        job.setNiceToHaveSkills(classified.niceToHave());
        job.setExperienceMinYears(experience.minYears());
        job.setExperienceMaxYears(experience.maxYears());
        applyOwner(job, owner);

        jobRepository.save(job);
        List<EvictedJobResponse> evicted = evictOldestBeyondCap();

        int skillCount = classified.mustHave().size() + classified.niceToHave().size();
        String reply = skillCount > 0
                ? "Got the job description — extracted " + skillCount + " skill" + (skillCount == 1 ? "" : "s") + "."
                : "Got the job description, but couldn't find any known skills in it — you can add them with \"paste skills only\".";

        return new JdUploadResponse(
                "success", reply,
                job.getId().toString(), job.getTitle(), job.jdNumberDisplay(),
                null, evicted.isEmpty() ? null : evicted,
                null, null, null, false
        );
    }

    @Override
    @Transactional
    public JdUploadResponse uploadJdSkillsOnly(JdSkillsOnlyRequest request, User owner) {
        List<String> skills = new ArrayList<>();
        for (String raw : request.skills().split("[,\\n]")) {
            String s = raw.strip();
            if (s.isEmpty()) continue;
            String canonical = s.toLowerCase(Locale.ROOT);
            if (skills.stream().noneMatch(existing -> existing.equalsIgnoreCase(canonical))) {
                skills.add(canonical);
            }
            if (skills.size() >= 30) break;
        }
        if (skills.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No skills found in that list — separate them with commas or new lines.");
        }

        String title = (request.title() == null || request.title().isBlank()) ? "Custom skill list" : request.title().strip();
        String contentHash = FileStorageService.sha256Hex(
                (title + "|" + String.join(",", skills)).getBytes(StandardCharsets.UTF_8));

        var existing = jobRepository.findByOwnerIdIgnoreCaseAndContentHash(owner.publicId(), contentHash);
        if (existing.isPresent()) {
            Job job = existing.get();
            return new JdUploadResponse(
                    "duplicate", "That exact skill list is already on file (" + job.jdNumberDisplay() + ").",
                    null, null, null, null, null,
                    job.getId().toString(), job.getTitle(), job.jdNumberDisplay(), false
            );
        }

        Job job = new Job();
        job.setTitle(title);
        job.setContentHash(contentHash);
        job.setMustHaveSkills(skills);
        applyOwner(job, owner);

        jobRepository.save(job);
        List<EvictedJobResponse> evicted = evictOldestBeyondCap();

        return new JdUploadResponse(
                "success", "Got it — " + skills.size() + " skill" + (skills.size() == 1 ? "" : "s") + " on file.",
                job.getId().toString(), job.getTitle(), job.jdNumberDisplay(),
                null, evicted.isEmpty() ? null : evicted,
                null, null, null, false
        );
    }

    @Override
    @Transactional
    public ResumeUploadResponse uploadResumes(UUID jobId, List<MultipartFile> files) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        List<String> existingFilenames = resumeRepository.findByJobIdOrderByUploadedAtDesc(jobId)
                .stream().map(Resume::getFilename).toList();
        List<String> takenNames = new ArrayList<>(existingFilenames);

        for (MultipartFile file : files) {
            byte[] bytes = readBytes(file);
            String filename = uniqueFilename(file.getOriginalFilename(), takenNames);
            takenNames.add(filename);

            String storedPath = storeFile(file, "jobs/" + jobId + "/resumes");
            String text = extractTextBestEffort(bytes, filename);

            Resume resume = new Resume();
            resume.setJob(job);
            resume.setFilename(filename);
            resume.setFilePath(storedPath);
            resume.setExtractedText(text);
            resume.setSizeBytes(bytes.length);
            resumeRepository.save(resume);
        }

        int count = files.size();
        String reply = "Got " + count + " resume" + (count == 1 ? "" : "s") + ".";
        return new ResumeUploadResponse("success", reply, true);
    }

    @Override
    public List<JobSummaryResponse> listJobs(User requester) {
        List<Job> jobs = requester.getRole() == Role.ADMIN
                ? jobRepository.findAllByOrderByCreatedAtDesc()
                : jobRepository.findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(requester.publicId());
        return jobs.stream().map(jobMapper::toSummaryResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public JobDetailResponse getJobDetail(UUID jobId) {
        Job job = getJobOr404(jobId);
        List<CandidateRowResponse> candidates = candidateRepository.findByJobIdOrderByFitScoreDesc(jobId)
                .stream().map(candidateMapper::toRowResponse).toList();
        return jobMapper.toDetailResponse(job, candidates);
    }

    @Override
    @Transactional
    public void deleteJob(UUID jobId, User requester) {
        Job job = getJobOr404(jobId);
        boolean isOwner = job.getOwnerId() != null && job.getOwnerId().equalsIgnoreCase(requester.publicId());
        if (requester.getRole() != Role.ADMIN && !isOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only delete your own job descriptions.");
        }
        if (job.getFilePath() != null) fileStorageService.delete(Path.of(job.getFilePath()));
        for (Resume resume : resumeRepository.findByJobIdOrderByUploadedAtDesc(jobId)) {
            fileStorageService.delete(Path.of(resume.getFilePath()));
        }
        jobRepository.delete(job);
    }

    @Override
    public List<ResumeSummaryResponse> listResumes(UUID jobId) {
        return resumeRepository.findByJobIdOrderByUploadedAtDesc(jobId).stream()
                .map(jobMapper::toResumeSummaryResponse).toList();
    }

    @Override
    public StoredFile loadJdFile(UUID jobId) {
        Job job = getJobOr404(jobId);
        if (job.getFilePath() == null) {
            // Skills-only JD, or uploaded before file persistence — fall back
            // to a plain-text export so the recruiter gets *something*.
            String text = job.getJdText() != null ? job.getJdText()
                    : "No original file on record for " + job.jdNumberDisplay() + ".";
            return new StoredFile(text.getBytes(StandardCharsets.UTF_8), job.jdNumberDisplay() + ".txt", "text/plain");
        }
        return new StoredFile(readFile(Path.of(job.getFilePath())), job.getOriginalFilename(), guessContentType(job.getOriginalFilename()));
    }

    @Override
    public StoredFile loadResumeFile(UUID jobId, String filename) {
        Resume resume = resumeRepository.findByJobIdAndFilename(jobId, filename)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Resume not found: " + filename));
        return new StoredFile(readFile(Path.of(resume.getFilePath())), resume.getFilename(), guessContentType(resume.getFilename()));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void applyOwner(Job job, User owner) {
        job.setOwnerId(owner.publicId());
        job.setOwnerName(owner.getName());
        job.setOwnerEmail(owner.getEmail());
        job.setOwnerRole(owner.getRole().name().toLowerCase(Locale.ROOT));
    }

    private Job getJobOr404(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
    }

    /** Newest `maxRetainedJobs` are kept; anything older is deleted (files
     *  included) and reported back so the frontend can tell the recruiter
     *  what got evicted. */
    private List<EvictedJobResponse> evictOldestBeyondCap() {
        List<Job> oldestFirst = jobRepository.findAllByOrderByCreatedAtAsc();
        int overflow = oldestFirst.size() - maxRetainedJobs;
        if (overflow <= 0) return List.of();

        List<EvictedJobResponse> evicted = new ArrayList<>();
        for (Job job : oldestFirst.subList(0, overflow)) {
            evicted.add(new EvictedJobResponse(job.getId().toString(), job.getJdNumber(), job.getTitle()));
            if (job.getFilePath() != null) fileStorageService.delete(Path.of(job.getFilePath()));
            for (Resume resume : resumeRepository.findByJobIdOrderByUploadedAtDesc(job.getId())) {
                fileStorageService.delete(Path.of(resume.getFilePath()));
            }
            jobRepository.delete(job);
        }
        return evicted;
    }

    private String storeFile(MultipartFile file, String subdir) {
        try {
            return fileStorageService.store(file, subdir).toString();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the uploaded file: " + e.getMessage());
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file: " + e.getMessage());
        }
    }

    private byte[] readFile(Path path) {
        try {
            return fileStorageService.readAll(path);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File is missing on disk.");
        }
    }

    /** Best-effort text extraction — a corrupt/unsupported file shouldn't
     *  fail the whole upload, just yield no extracted skills/text. */
    private String extractTextBestEffort(byte[] bytes, String filename) {
        try {
            return textExtractor.extract(new ByteArrayInputStream(bytes), filename);
        } catch (IOException e) {
            return "";
        }
    }

    private static String titleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "Untitled role";
        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        String spaced = base.replaceAll("[_\\-]+", " ").strip();
        if (spaced.isEmpty()) return "Untitled role";
        String[] words = spaced.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.length() > 1 ? w.substring(1) : "");
        }
        return sb.toString();
    }

    /** Avoids collisions when two resumes share an original filename within
     *  the same job — Candidates.jsx/Jobs.jsx build resume download URLs
     *  directly from this filename, so it must stay unique per job. */
    private static String uniqueFilename(String original, List<String> taken) {
        String name = (original == null || original.isBlank()) ? "resume" : original;
        if (!taken.contains(name)) return name;
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        int n = 2;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (taken.contains(candidate));
        return candidate;
    }

    private static String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
