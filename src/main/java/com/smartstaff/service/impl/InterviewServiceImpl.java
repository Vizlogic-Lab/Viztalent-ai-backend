package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.InterviewInviteMintRequest;
import com.smartstaff.dto.request.InterviewPrepareRequest;
import com.smartstaff.dto.request.InterviewSaveByTokenRequest;
import com.smartstaff.dto.request.InterviewSaveRequest;
import com.smartstaff.dto.request.TranscriptTurnRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Interview;
import com.smartstaff.entity.InterviewTurn;
import com.smartstaff.entity.Invite;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.InterviewMapper;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.InterviewTurnRepository;
import com.smartstaff.repository.InviteRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.InterviewService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Self-service + browser AI interviews (SMARTSTAFF_BACKEND_DESIGN.md §4.5,
 *  Phase 7). Twilio phone calls (place_call / call_status) are Phase 8 and
 *  not built here — see docs/FEATURES.md. */
@Service
public class InterviewServiceImpl implements InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int QUESTION_COUNT = 5;
    private static final String DEFAULT_LANGUAGE = "en-IN";

    private final JobRepository jobRepository;
    private final CandidateRepository candidateRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewTurnRepository interviewTurnRepository;
    private final InviteRepository inviteRepository;
    private final InterviewMapper interviewMapper;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;

    public InterviewServiceImpl(JobRepository jobRepository,
                                 CandidateRepository candidateRepository,
                                 InterviewRepository interviewRepository,
                                 InterviewTurnRepository interviewTurnRepository,
                                 InviteRepository inviteRepository,
                                 InterviewMapper interviewMapper,
                                 SettingsService settingsService,
                                 ObjectMapper objectMapper,
                                 GeminiClient geminiClient) {
        this.jobRepository = jobRepository;
        this.candidateRepository = candidateRepository;
        this.interviewRepository = interviewRepository;
        this.interviewTurnRepository = interviewTurnRepository;
        this.inviteRepository = inviteRepository;
        this.interviewMapper = interviewMapper;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
    }

    @Override
    public InterviewConfigResponse config() {
        return new InterviewConfigResponse(settingsService.isTwilioConfigured(), settingsService.getTwilioFromNumberOrNull());
    }

    @Override
    @Transactional
    public InterviewPrepareResponse prepare(InterviewPrepareRequest req) {
        PreparedInterview prepared = prepareInterview(
                req.session_id(), req.candidate_name(), req.phone(), req.file_name(), req.language(), "BROWSER");
        return interviewMapper.toPrepareResponse(prepared.interview(), prepared.turns());
    }

    @Override
    @Transactional
    public InterviewMintResponse mintInvite(InterviewInviteMintRequest req, User admin) {
        if (req.candidate_email() == null || req.candidate_email().isBlank()) {
            return InterviewMintResponse.error("candidate_email is required.");
        }

        PreparedInterview prepared;
        try {
            prepared = prepareInterview(
                    req.session_id(), req.candidate_name(), req.phone(), req.file_name(), req.language(), "SELF");
        } catch (ApiException e) {
            // Same prep step as prepare() above, but this call site reports
            // failure as a normal 200 {status:"error"} instead of an HTTP
            // error — see InterviewMintResponse's javadoc.
            return InterviewMintResponse.error(e.getMessage());
        }

        String rawToken = randomToken();
        Invite invite = new Invite();
        invite.setTokenHash(FileStorageService.sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8)));
        invite.setKind("INTERVIEW");
        invite.setJob(prepared.interview().getJob());
        invite.setInterview(prepared.interview());
        invite.setCandidateEmail(req.candidate_email().trim());
        invite.setCandidateName(req.candidate_name());
        invite.setCombined(false);
        invite.setExpiresAt(Instant.now().plusSeconds(settingsService.getInviteTtlSeconds()));
        invite.setCreatedBy(admin == null ? null : admin.publicId());
        inviteRepository.save(invite);

        String url = settingsService.getPublicBaseUrl() + "/interview/" + rawToken;
        return InterviewMintResponse.success(url);
    }

    @Override
    @Transactional
    public SimpleResponse save(InterviewSaveRequest req) {
        UUID jobId = parseUuid(req.session_id(), "No job description found for this session — upload a JD first.");
        UUID interviewId = parseUuid(req.interview_id(), "Unknown interview.");

        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interview not found."));
        if (!interview.getJob().getId().equals(jobId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview doesn't belong to that job.");
        }

        if (req.candidate_name() != null) interview.setCandidateName(req.candidate_name());
        if (req.phone() != null) interview.setPhone(req.phone());
        if (req.role_title() != null) interview.setRoleTitle(req.role_title());
        completeInterview(interview, req.transcript(), req.started_at(), req.ended_at());
        return SimpleResponse.OK;
    }

    @Override
    @Transactional(readOnly = true)
    public InterviewPrepareResponse byToken(String token) {
        Invite invite = resolveInterviewInvite(token);
        if (invite.isExpired()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview link has expired.");
        }
        if (invite.getUsedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview has already been completed.");
        }
        Interview interview = invite.getInterview();
        List<InterviewTurn> turns = interviewTurnRepository.findByInterviewIdOrderBySeqAsc(interview.getId());
        return interviewMapper.toPrepareResponse(interview, turns);
    }

    @Override
    @Transactional
    public SimpleResponse saveByToken(String token, InterviewSaveByTokenRequest req) {
        Invite invite = resolveInterviewInvite(token);

        // Atomic single-use redemption — one UPDATE, guards expiry AND
        // already-used (including a concurrent request winning the race) in
        // a single WHERE clause, per the design doc's "consume with an
        // atomic UPDATE ... WHERE used_at IS NULL AND expires_at > now()".
        int consumed = inviteRepository.consume(invite.getId(), Instant.now());
        if (consumed == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview has already been completed.");
        }

        Interview interview = invite.getInterview();
        completeInterview(interview, req.transcript(), req.started_at(), req.ended_at());
        return SimpleResponse.OK;
    }

    @Override
    @Transactional(readOnly = true)
    public InterviewTranscriptsResponse transcripts(UUID jobId) {
        List<Interview> interviews = interviewRepository.findByJobIdAndStatusOrderByCreatedAtDesc(jobId, "COMPLETED");
        List<InterviewTranscriptResponse> rows = interviews.stream()
                .map(iv -> interviewMapper.toTranscriptResponse(iv, interviewTurnRepository.findByInterviewIdOrderBySeqAsc(iv.getId())))
                .toList();
        return new InterviewTranscriptsResponse(true, rows);
    }

    // ── shared prep / save logic ───────────────────────────────────────

    private record PreparedInterview(Interview interview, List<InterviewTurn> turns) {}

    private PreparedInterview prepareInterview(String sessionId, String candidateName, String phone,
                                                String fileName, String language, String mode) {
        UUID jobId = parseUuid(sessionId, "No job description found for this session — upload a JD first.");
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        Candidate candidate = (fileName == null || fileName.isBlank())
                ? null
                : candidateRepository.findByJobIdAndResumeFilename(jobId, fileName).orElse(null);

        String lang = (language == null || language.isBlank()) ? DEFAULT_LANGUAGE : language.trim();
        String name = (candidateName == null || candidateName.isBlank())
                ? (candidate != null ? candidate.getCandidateName() : "the candidate")
                : candidateName.trim();

        GeneratedInterview content = generateWithGemini(job, candidate, name, lang);

        Interview interview = new Interview();
        interview.setJob(job);
        interview.setCandidate(candidate);
        interview.setMode(mode);
        interview.setStatus("PENDING");
        interview.setLanguage(lang);
        interview.setRoleTitle(job.getTitle());
        interview.setCandidateName(name);
        interview.setPhone(phone);
        interview.setIntro(content.intro());
        interview.setOutro(content.outro());
        interviewRepository.save(interview);

        List<InterviewTurn> turns = new ArrayList<>();
        int seq = 0;
        for (GeneratedQuestion q : content.questions()) {
            InterviewTurn turn = new InterviewTurn();
            turn.setInterview(interview);
            turn.setSeq(seq++);
            turn.setCategory(q.category());
            turn.setSkill(q.skill());
            turn.setQuestion(q.question());
            turn.setAnswer("");
            interviewTurnRepository.save(turn);
            turns.add(turn);
        }

        return new PreparedInterview(interview, turns);
    }

    private void completeInterview(Interview interview, List<TranscriptTurnRequest> transcript,
                                    Instant startedAt, Instant endedAt) {
        interviewTurnRepository.deleteByInterviewId(interview.getId());
        int seq = 0;
        for (TranscriptTurnRequest t : transcript) {
            InterviewTurn turn = new InterviewTurn();
            turn.setInterview(interview);
            turn.setSeq(seq++);
            turn.setCategory(t.category());
            turn.setSkill(t.skill());
            turn.setQuestion(t.question());
            turn.setAnswer(t.answer() == null ? "" : t.answer());
            interviewTurnRepository.save(turn);
        }
        interview.setStatus("COMPLETED");
        interview.setStartedAt(startedAt != null ? startedAt : interview.getCreatedAt());
        interview.setEndedAt(endedAt != null ? endedAt : Instant.now());
        interviewRepository.save(interview);
    }

    private Invite resolveInterviewInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview link is invalid.");
        }
        String hash = FileStorageService.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
        Invite invite = inviteRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "This interview link is invalid."));
        if (!"INTERVIEW".equals(invite.getKind()) || invite.getInterview() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This interview link is invalid.");
        }
        return invite;
    }

    // ── Gemini question generation ─────────────────────────────────────

    private record GeneratedQuestion(String category, String skill, String question) {}
    private record GeneratedInterview(String intro, String outro, List<GeneratedQuestion> questions) {}

    private GeneratedInterview generateWithGemini(Job job, Candidate candidate, String candidateName, String language) {
        String key = settingsService.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No Gemini API key configured. Add one in Settings before starting an AI interview.");
        }

        List<String> skills = job.allSkills();
        String skillsCsv = skills.isEmpty() ? "general software engineering" : String.join(", ", skills);
        String candidateContext = candidate != null
                ? "The candidate's résumé matched these skills: " + String.join(", ", candidate.getMatchedSkills())
                    + ". Years of experience: " + candidate.getYearsExperience() + "."
                : "No résumé details are available for this candidate — ask more general questions.";

        String prompt = """
                You are preparing a short, friendly L1 (first-round) voice interview for the role "%s".
                Relevant skills for this role: %s.
                Candidate: %s. %s

                Write exactly %d interview questions spanning a mix of categories — background, a couple of
                technical/role-specific questions grounded in the skills above, one behavioral question, and a
                closing question. Keep each question conversational (one sentence, no multi-part questions) —
                this will be read aloud by a text-to-speech voice and answered out loud. Also write a short,
                warm one-paragraph intro (greet the candidate by name, explain this is a short AI-conducted L1
                interview) and a short outro (thank them, say the team will follow up).

                Write the intro, outro, and every question in the language for locale code "%s".

                Respond with ONLY a JSON object, no markdown fences, no commentary, shaped exactly like:
                {"intro":"...","outro":"...","questions":[{"category":"Background","skill":null,"question":"..."}]}
                """.formatted(job.getTitle(), skillsCsv, candidateName, candidateContext, QUESTION_COUNT, language);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.7)
        );

        String raw;
        try {
            raw = geminiClient.generateContent(key, body);
        } catch (Exception e) {
            log.warn("Interview question generation failed for job {}: {}", job.getId(), e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI service to prepare interview questions. Please try again.");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            JsonNode obj = objectMapper.readTree(text);

            String intro = obj.path("intro").asText("Hi! Thanks for joining this short interview.");
            String outro = obj.path("outro").asText("Thank you for your time — we'll be in touch soon.");
            List<GeneratedQuestion> questions = new ArrayList<>();
            for (JsonNode q : obj.path("questions")) {
                String question = q.path("question").asText(null);
                if (question == null || question.isBlank()) continue;
                questions.add(new GeneratedQuestion(
                        q.path("category").asText("General"),
                        q.hasNonNull("skill") ? q.path("skill").asText(null) : null,
                        question));
            }
            if (questions.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service didn't return any interview questions. Please try again.");
            }
            return new GeneratedInterview(intro, outro, questions);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Interview question generation returned unparseable output for job {}: {}", job.getId(), e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service returned something unexpected. Please try again.");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID parseUuid(String value, String errorMessage) {
        try {
            return UUID.fromString(value.trim());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }
}
