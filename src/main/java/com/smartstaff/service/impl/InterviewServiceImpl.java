package com.smartstaff.service.impl;

import com.smartstaff.ai.GeminiException;
import com.smartstaff.ai.AiFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.InterviewInviteMintRequest;
import com.smartstaff.dto.request.InterviewPrepareRequest;
import com.smartstaff.dto.request.InterviewSaveByTokenRequest;
import com.smartstaff.dto.request.InterviewSaveRequest;
import com.smartstaff.dto.request.TranscriptTurnRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.InterviewMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.InterviewCompletedEvent;
import com.smartstaff.service.InterviewService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.FileStorageService;
import com.smartstaff.util.InviteLinks;
import com.smartstaff.util.RoleProfileRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Self-service + browser AI interviews (SMARTSTAFF_BACKEND_DESIGN.md §4.5,
 *  Phase 7); phone calls are PhoneInterviewServiceImpl.
 *
 *  F16: an interview asks the job's recruiter questions first (in their
 *  order), then Gemini fills up to the job's total (default 5, max 10) with
 *  role-targeted questions including 1-2 practical prompts (HANDS_ON), and
 *  writes expected points for recruiter questions that have none (kept on the
 *  job question, editable by HR). Gemini runs between two short transactions,
 *  never inside one. A completed interview is graded by InterviewEvaluationJob. */
@Service
public class InterviewServiceImpl implements InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewServiceImpl.class);
    private static final String DEFAULT_LANGUAGE = "en-IN";

    private final JobRepository jobRepository;
    private final CandidateRepository candidateRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewTurnRepository interviewTurnRepository;
    private final InviteRepository inviteRepository;
    private final JobInterviewQuestionRepository jobQuestions;
    private final JobRoleProfileRepository roleProfiles;
    private final SmeReviewRepository reviews;
    private final UserRepository users;
    private final InterviewMapper interviewMapper;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;
    private final InterviewEvaluationJob evaluation;
    private final ApplicationEventPublisher events;
    private final EmailService emailService;
    private final TransactionTemplate tx;

    public InterviewServiceImpl(JobRepository jobRepository,
                                 CandidateRepository candidateRepository,
                                 InterviewRepository interviewRepository,
                                 InterviewTurnRepository interviewTurnRepository,
                                 InviteRepository inviteRepository,
                                 JobInterviewQuestionRepository jobQuestions,
                                 JobRoleProfileRepository roleProfiles,
                                 SmeReviewRepository reviews,
                                 UserRepository users,
                                 InterviewMapper interviewMapper,
                                 SettingsService settingsService,
                                 ObjectMapper objectMapper,
                                 GeminiClient geminiClient,
                                 InterviewEvaluationJob evaluation,
                                 ApplicationEventPublisher events,
                                 PlatformTransactionManager transactionManager,
                                 EmailService emailService) {
        this.emailService = emailService;
        this.jobRepository = jobRepository;
        this.candidateRepository = candidateRepository;
        this.interviewRepository = interviewRepository;
        this.interviewTurnRepository = interviewTurnRepository;
        this.inviteRepository = inviteRepository;
        this.jobQuestions = jobQuestions;
        this.roleProfiles = roleProfiles;
        this.reviews = reviews;
        this.users = users;
        this.interviewMapper = interviewMapper;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
        this.evaluation = evaluation;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public InterviewConfigResponse config() {
        return new InterviewConfigResponse(settingsService.isTwilioConfigured(), settingsService.getTwilioFromNumberOrNull());
    }

    @Override
    public InterviewPrepareResponse prepare(InterviewPrepareRequest req) {
        PreparedInterview prepared = prepareInterview(
                req.session_id(), req.candidate_name(), req.phone(), req.file_name(), req.language(), "BROWSER");
        return interviewMapper.toPrepareResponse(prepared.interview(), prepared.turns());
    }

    @Override
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

        String rawToken = InviteLinks.newToken();
        String url = InviteLinks.interview(settingsService.getPublicBaseUrl(), rawToken);
        EmailService.Queued email = tx.execute(s -> {
            Interview interview = interviewRepository.getReferenceById(prepared.interview().getId());
            Invite invite = new Invite();
            invite.setTokenHash(InviteLinks.hash(rawToken));
            invite.setKind("INTERVIEW");
            invite.setJob(jobRepository.getReferenceById(prepared.jobId()));
            invite.setInterview(interview);
            invite.setCandidateEmail(req.candidate_email().trim());
            invite.setCandidateName(req.candidate_name());
            invite.setCombined(false);
            invite.setExpiresAt(Instant.now().plusSeconds(settingsService.getInviteTtlSeconds()));
            invite.setCreatedBy(admin == null ? null : admin.publicId());
            inviteRepository.save(invite);
            return Boolean.TRUE.equals(req.send_email())
                    ? emailService.queueInvite(invite, prepared.interview().getRoleTitle(), url, rawToken, admin) : null;
        });
        return InterviewMintResponse.success(url, email);
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
        if ("COMPLETED".equals(interview.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "This interview has already been completed.", "interview_completed");
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
        List<InterviewTranscriptResponse> rows = interviews.stream().map(iv -> {
            SmeReview review = reviews.findBySubjectTypeAndSubjectId(SmeReview.Subject.INTERVIEW, iv.getId()).orElse(null);
            String reviewer = review == null || review.getReviewerId() == null ? null
                    : users.findById(review.getReviewerId()).map(User::getName).orElse(null);
            return interviewMapper.toTranscriptResponse(iv, interviewTurnRepository.findByInterviewIdOrderBySeqAsc(iv.getId()),
                    review, reviewer);
        }).toList();
        return new InterviewTranscriptsResponse(true, rows);
    }

    @Override
    public Map<String, Object> regrade(UUID interviewId) {
        tx.executeWithoutResult(s -> {
            Interview iv = interviewRepository.findById(interviewId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interview not found."));
            if (!"COMPLETED".equals(iv.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "Only a completed interview can be graded.", "not_completed");
            }
            if ("VALIDATED".equals(iv.getEvaluationStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "This result is validated and locked; an admin can reopen its review.",
                        "result_locked");
            }
        });
        evaluation.rerun(interviewId);   // async; inline in tests
        return Map.of("ok", true, "status", "queued");
    }

    // ── shared prep / save logic ───────────────────────────────────────

    private record PreparedInterview(UUID jobId, Interview interview, List<InterviewTurn> turns) {}

    /** What prep needs, read in one short transaction. */
    private record Context(UUID jobId, String jobTitle, List<String> skills, Candidate candidate, String candidateName,
                           String language, List<JobInterviewQuestion> recruiter, int total, JobRoleProfile profile) {}

    private PreparedInterview prepareInterview(String sessionId, String candidateName, String phone,
                                                String fileName, String language, String mode) {
        UUID jobId = parseUuid(sessionId, "No job description found for this session — upload a JD first.");
        Context ctx = tx.execute(s -> context(jobId, candidateName, fileName, language));
        GeneratedInterview content = generateWithGemini(ctx);
        return tx.execute(s -> persist(ctx, content, phone, mode));
    }

    private Context context(UUID jobId, String candidateName, String fileName, String language) {
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
        List<JobInterviewQuestion> recruiter = jobQuestions.findByJobIdOrderBySeqAsc(jobId);
        if (recruiter.size() > InterviewQuestionService.MAX_QUESTIONS) {
            recruiter = recruiter.subList(0, InterviewQuestionService.MAX_QUESTIONS);
        }
        JobRoleProfile profile = roleProfiles.findById(jobId).orElseGet(() -> RoleProfileRules.fallback(job));
        return new Context(jobId, job.getTitle(), new ArrayList<>(job.allSkills()), candidate, name, lang,
                List.copyOf(recruiter), InterviewQuestionService.total(job), profile);
    }

    private PreparedInterview persist(Context ctx, GeneratedInterview content, String phone, String mode) {
        Interview interview = new Interview();
        interview.setJob(jobRepository.getReferenceById(ctx.jobId()));
        interview.setCandidate(ctx.candidate() == null ? null : candidateRepository.getReferenceById(ctx.candidate().getId()));
        interview.setMode(mode);
        interview.setStatus("PENDING");
        interview.setLanguage(ctx.language());
        interview.setRoleTitle(ctx.jobTitle());
        interview.setCandidateName(ctx.candidateName());
        interview.setPhone(phone);
        interview.setIntro(content.intro());
        interview.setOutro(content.outro());
        interviewRepository.save(interview);

        List<InterviewTurn> turns = new ArrayList<>();
        for (int i = 0; i < ctx.recruiter().size(); i++) {
            JobInterviewQuestion q = ctx.recruiter().get(i);
            List<String> points = q.getExpectedPoints();
            List<String> written = content.recruiterPoints().get(i + 1);
            if (points.isEmpty() && written != null) {
                points = written;
                // Kept on the job question (unless HR filled it meanwhile) so later interviews reuse them and HR can edit them.
                jobQuestions.findById(q.getId()).filter(current -> current.getExpectedPoints().isEmpty()).ifPresent(current -> {
                    current.setExpectedPoints(new ArrayList<>(written));
                    current.setExpectedPointsSource("AI");
                    current.setUpdatedAt(Instant.now());
                    jobQuestions.save(current);
                });
            }
            turns.add(turn(interview, turns.size(), "RECRUITER", q.getCategory() == null ? "Recruiter" : q.getCategory(),
                    q.getSkill(), q.getQuestion(), q.getDimension(), points));
        }
        for (GeneratedQuestion g : content.questions()) {
            turns.add(turn(interview, turns.size(), "AI", g.category(), g.skill(), g.question(), g.dimension(), g.expectedPoints()));
        }
        return new PreparedInterview(ctx.jobId(), interview, turns);
    }

    private InterviewTurn turn(Interview interview, int seq, String source, String category, String skill, String question,
                               Dimension dimension, List<String> expectedPoints) {
        InterviewTurn turn = new InterviewTurn();
        turn.setInterview(interview);
        turn.setSeq(seq);
        turn.setSource(source);
        turn.setCategory(category);
        turn.setSkill(skill);
        turn.setQuestion(question);
        turn.setDimension(dimension == null ? Dimension.THEORY : dimension);
        turn.setExpectedPoints(new ArrayList<>(expectedPoints));
        turn.setAnswer("");
        return interviewTurnRepository.save(turn);
    }

    private void completeInterview(Interview interview, List<TranscriptTurnRequest> transcript,
                                    Instant startedAt, Instant endedAt) {
        Map<Integer, InterviewTurn> bySeq = new HashMap<>();
        for (InterviewTurn turn : interviewTurnRepository.findByInterviewIdOrderBySeqAsc(interview.getId())) {
            bySeq.put(turn.getSeq(), turn);
        }
        for (int i = 0; i < transcript.size(); i++) {
            TranscriptTurnRequest t = transcript.get(i);
            InterviewTurn turn = bySeq.get(t.seq() != null ? t.seq() : i);
            if (turn == null) continue;
            turn.setAnswer(t.answer() == null ? "" : t.answer());
            interviewTurnRepository.save(turn);
        }
        interview.setStatus("COMPLETED");
        interview.setStartedAt(startedAt != null ? startedAt : interview.getCreatedAt());
        interview.setEndedAt(endedAt != null ? endedAt : Instant.now());
        interviewRepository.save(interview);
        events.publishEvent(new InterviewCompletedEvent(interview.getId()));
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

    // ── Gemini: AI questions + expected points (outside any transaction) ─

    record GeneratedQuestion(String category, String skill, String question, Dimension dimension, List<String> expectedPoints) {}

    /** recruiterPoints: 1-based recruiter question number → expected points the AI wrote for it. */
    record GeneratedInterview(String intro, String outro, List<GeneratedQuestion> questions, Map<Integer, List<String>> recruiterPoints) {}

    /** AI questions to add and how many of them are practical (1-2). */
    static int[] split(int recruiterCount, int total) {
        int ai = Math.max(0, total - recruiterCount);
        int practical = ai == 0 ? 0 : ai >= 3 ? 2 : 1;
        return new int[]{ai - practical, practical};
    }

    private GeneratedInterview generateWithGemini(Context ctx) {
        String key = settingsService.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No Gemini API key configured. Add one in Settings before starting an AI interview.");
        }
        int[] split = split(ctx.recruiter().size(), ctx.total());
        int theory = split[0], practical = split[1];
        List<Integer> needPoints = new ArrayList<>();
        for (int i = 0; i < ctx.recruiter().size(); i++) {
            if (ctx.recruiter().get(i).getExpectedPoints().isEmpty()) needPoints.add(i + 1);
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt(ctx, theory, practical, needPoints))))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema(), "temperature", 0.5)
        );

        String raw;
        try {
            raw = geminiClient.generateContent(key, body, AiFeature.INTERVIEW);
        } catch (GeminiException e) {
            log.warn("Interview question generation failed for job {}: {}", ctx.jobId(), e.getCode());
            if (e.unusable()) throw e;   // quota / key / model / cap: say exactly what to fix
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI service to prepare interview questions. Please try again.");
        } catch (Exception e) {
            log.warn("Interview question generation failed for job {}: {}", ctx.jobId(), e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI service to prepare interview questions. Please try again.");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            return parse(objectMapper.readTree(text), theory, practical, needPoints, ctx.recruiter().size());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Interview question generation returned unparseable output for job {}: {}", ctx.jobId(), e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service returned something unexpected. Please try again.");
        }
    }

    private static String prompt(Context ctx, int theory, int practical, List<Integer> needPoints) {
        JobRoleProfile p = ctx.profile();
        String skills = ctx.skills().isEmpty() ? "general software engineering" : String.join(", ", ctx.skills());
        String weighted = p.getSkillWeights() == null || p.getSkillWeights().isEmpty() ? skills
                : p.getSkillWeights().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(e -> e.getKey() + " (weight " + e.getValue() + ")").collect(Collectors.joining(", "));
        String candidateContext = ctx.candidate() != null
                ? "The candidate's résumé matched these skills: " + String.join(", ", ctx.candidate().getMatchedSkills())
                    + ". Years of experience: " + ctx.candidate().getYearsExperience() + "."
                : "No résumé details are available for this candidate.";

        StringBuilder sb = new StringBuilder();
        sb.append("You are preparing a short, friendly L1 (first-round) voice interview for the role \"").append(ctx.jobTitle()).append("\".\n")
                .append("Role profile: family ").append(p.getRoleFamily()).append(", seniority ").append(p.getSeniority())
                .append(", languages ").append(p.getLanguages() == null ? "-" : String.join(", ", p.getLanguages()))
                .append(". Skills by importance: ").append(weighted).append(".\n")
                .append("Candidate: ").append(ctx.candidateName()).append(". ").append(candidateContext).append("\n\n");
        if (!ctx.recruiter().isEmpty()) {
            sb.append("The recruiter will ask these questions first (do not repeat or rephrase them):\n");
            for (int i = 0; i < ctx.recruiter().size(); i++) {
                sb.append("R").append(i + 1).append(": ").append(ctx.recruiter().get(i).getQuestion()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Write exactly ").append(theory).append(" item(s) in \"questions\": conversational questions on the role's ")
                .append("concepts, background and experience, grounded in the skills above. Write exactly ").append(practical)
                .append(" item(s) in \"practical_questions\": practical prompts that make the candidate reason through real ")
                .append("work, such as \"Walk me through how you would debug ...\" or \"How would you design ...\". Every ")
                .append("question is one spoken sentence (it is read aloud and answered out loud), answerable with substance ")
                .append("— no yes/no questions and no \"do you have questions for us\".\n")
                .append("For every question give 2 to 4 expected_points: the specific things a good answer covers, each a short ")
                .append("phrase a grader can check against the spoken answer.\n");
        if (!needPoints.isEmpty()) {
            sb.append("Also write 2 to 4 expected_points for each of these recruiter questions, in \"recruiter_expected_points\" ")
                    .append("with its number (index): ")
                    .append(needPoints.stream().map(n -> "R" + n).collect(Collectors.joining(", "))).append(".\n");
        }
        sb.append("Also write a short, warm one-paragraph intro (greet the candidate by name, explain this is a short ")
                .append("AI-conducted L1 interview) and a short outro (thank them, say the team will follow up).\n")
                .append("Write the intro, outro, every question and every expected point in the language for locale code \"")
                .append(ctx.language()).append("\".\n");
        return sb.toString();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> question = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING"),
                        "skill", Map.of("type", "STRING"),
                        "question", Map.of("type", "STRING"),
                        "expected_points", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("category", "question", "expected_points"));
        Map<String, Object> recruiter = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "index", Map.of("type", "INTEGER"),
                        "expected_points", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("index", "expected_points"));
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "intro", Map.of("type", "STRING"),
                        "outro", Map.of("type", "STRING"),
                        "questions", Map.of("type", "ARRAY", "items", question),
                        "practical_questions", Map.of("type", "ARRAY", "items", question),
                        "recruiter_expected_points", Map.of("type", "ARRAY", "items", recruiter)),
                "required", List.of("intro", "outro", "questions", "practical_questions", "recruiter_expected_points"));
    }

    /** Takes the requested number of each kind (a short list is topped up from the other kind);
     *  a question without expected points can't be graded, so it is skipped. */
    static GeneratedInterview parse(JsonNode obj, int theory, int practical, List<Integer> needPoints, int recruiterCount) {
        String intro = obj.path("intro").asText("Hi! Thanks for joining this short interview.");
        String outro = obj.path("outro").asText("Thank you for your time — we'll be in touch soon.");
        Deque<GeneratedQuestion> concept = questions(obj.path("questions"), Dimension.THEORY);
        Deque<GeneratedQuestion> applied = questions(obj.path("practical_questions"), Dimension.HANDS_ON);
        List<GeneratedQuestion> out = new ArrayList<>();
        for (int i = 0; i < theory && !concept.isEmpty(); i++) out.add(concept.poll());
        for (int i = 0; i < practical && !applied.isEmpty(); i++) out.add(applied.poll());
        while (out.size() < theory + practical && !(concept.isEmpty() && applied.isEmpty())) {
            out.add(concept.isEmpty() ? applied.poll() : concept.poll());
        }
        if (out.isEmpty() && recruiterCount == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service didn't return any interview questions. Please try again.");
        }
        Map<Integer, List<String>> recruiterPoints = new HashMap<>();
        for (JsonNode r : obj.path("recruiter_expected_points")) {
            int index = r.path("index").asInt(-1);
            List<String> points = points(r.path("expected_points"));
            if (needPoints.contains(index) && !points.isEmpty()) recruiterPoints.put(index, points);
        }
        return new GeneratedInterview(intro, outro, out, recruiterPoints);
    }

    private static Deque<GeneratedQuestion> questions(JsonNode array, Dimension dimension) {
        Deque<GeneratedQuestion> out = new ArrayDeque<>();
        for (JsonNode q : array) {
            String question = q.path("question").asText("").strip();
            List<String> points = points(q.path("expected_points"));
            if (question.isEmpty() || points.isEmpty()) continue;
            String skill = q.path("skill").asText("").strip();
            out.add(new GeneratedQuestion(q.path("category").asText(dimension == Dimension.HANDS_ON ? "Practical" : "General"),
                    skill.isEmpty() ? null : skill, question, dimension, points));
        }
        return out;
    }

    private static List<String> points(JsonNode array) {
        List<String> raw = new ArrayList<>();
        array.forEach(p -> raw.add(p.asText("")));
        List<String> clean = InterviewQuestionService.cleanPoints(raw);
        return clean.size() > InterviewQuestionService.MAX_EXPECTED_POINTS
                ? clean.subList(0, InterviewQuestionService.MAX_EXPECTED_POINTS) : clean;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static UUID parseUuid(String value, String errorMessage) {
        try {
            return UUID.fromString(value.trim());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }
}
