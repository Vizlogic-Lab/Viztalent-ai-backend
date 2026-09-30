package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.RoleProfileRequest;
import com.smartstaff.dto.response.RoleProfileResponse;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.ProfileSource;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.JobRoleProfileRepository;
import com.smartstaff.service.RoleProfileService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.RoleProfileRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class RoleProfileServiceImpl implements RoleProfileService {

    private static final Logger log = LoggerFactory.getLogger(RoleProfileServiceImpl.class);
    private static final int JD_CHARS = 4000;

    private final JobRepository jobRepository;
    private final JobRoleProfileRepository roleProfileRepository;
    private final SettingsService settingsService;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTransaction;

    public RoleProfileServiceImpl(JobRepository jobRepository,
                                  JobRoleProfileRepository roleProfileRepository,
                                  SettingsService settingsService,
                                  GeminiClient geminiClient,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.roleProfileRepository = roleProfileRepository;
        this.settingsService = settingsService;
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
        this.newTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: when run inline after a commit, the finished upload
        // transaction is still bound to the thread and would swallow the write.
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Async("profileExtractorExecutor")
    public void extractRoleProfile(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || isHrEdited(roleProfileRepository.findById(jobId).orElse(null))) return;

        JobRoleProfile profile = null;
        String key = settingsService.getGeminiApiKeyOrNull();
        if (key != null && !key.isBlank()) {
            try {
                profile = extractViaGemini(job, key);
            } catch (Exception e) {
                log.warn("Role profile via Gemini failed for job {}, using rules: {}", jobId, e.toString());
            }
        }
        if (profile == null) profile = RoleProfileRules.fallback(job);

        JobRoleProfile result = profile;
        newTransaction.executeWithoutResult(status -> {
            if (!jobRepository.existsById(jobId)) return;
            if (isHrEdited(roleProfileRepository.findById(jobId).orElse(null))) return;
            roleProfileRepository.save(result);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public RoleProfileResponse getRoleProfile(UUID jobId) {
        return findRoleProfile(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "The role profile for this job is still being prepared — try again in a moment.", "role_profile_pending"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleProfileResponse> findRoleProfile(UUID jobId) {
        return roleProfileRepository.findById(jobId).map(RoleProfileServiceImpl::toResponse);
    }

    @Override
    @Transactional
    public RoleProfileResponse updateRoleProfile(UUID jobId, RoleProfileRequest req, User editor) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
        JobRoleProfile profile = roleProfileRepository.findById(jobId).orElseGet(() -> RoleProfileRules.fallback(job));

        if (req.role_family() != null) {
            profile.setRoleFamily(parse(RoleFamily.class, req.role_family(), "role_family"));
            if (req.is_technical() == null) profile.setIsTechnical(profile.getRoleFamily() != RoleFamily.NON_TECHNICAL);
        }
        if (req.is_technical() != null) profile.setIsTechnical(req.is_technical());
        if (req.seniority() != null) profile.setSeniority(parse(SeniorityLevel.class, req.seniority(), "seniority"));
        if (req.languages() != null) profile.setLanguages(RoleProfileRules.normaliseLanguages(req.languages(), true));
        if (req.frameworks() != null) {
            profile.setFrameworks(req.frameworks().stream().map(String::trim).filter(f -> !f.isEmpty()).distinct().limit(15).toList());
        }
        if (req.skill_weights() != null) profile.setSkillWeights(validatedWeights(req.skill_weights()));

        profile.setSource(ProfileSource.HR);
        profile.setEditedBy(editor.getId());
        profile.setUpdatedAt(Instant.now());
        return toResponse(roleProfileRepository.save(profile));
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    private JobRoleProfile extractViaGemini(Job job, String key) throws Exception {
        String jd = job.getJdText() == null ? "" : job.getJdText();
        if (jd.length() > JD_CHARS) jd = jd.substring(0, JD_CHARS);

        String prompt = """
                Build a role profile for this job so we can write a role-specific skills assessment.

                Job title: %s
                Must-have skills: %s
                Nice-to-have skills: %s
                Experience: %s
                Job description (may be truncated):
                %s

                Rules:
                - role_family is the closest match; use NON_TECHNICAL for sales, operations, HR and other roles without programming.
                - languages are programming languages the candidate must write (e.g. java, python, javascript, cpp); empty if none.
                - skill_weights: every skill the job really needs, including domain skills (e.g. DMS, SFA), \
                weight 1 (minor) to 5 (critical). Only use skills that appear in the job description or skill lists.
                """.formatted(job.getTitle(), String.join(", ", job.getMustHaveSkills()),
                String.join(", ", job.getNiceToHaveSkills()), experience(job), jd);

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "role_family", Map.of("type", "STRING", "enum", names(RoleFamily.values())),
                        "is_technical", Map.of("type", "BOOLEAN"),
                        "languages", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "frameworks", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "seniority", Map.of("type", "STRING", "enum", names(SeniorityLevel.values())),
                        "skill_weights", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of("skill", Map.of("type", "STRING"), "weight", Map.of("type", "INTEGER")),
                                "required", List.of("skill", "weight")))),
                "required", List.of("role_family", "is_technical", "languages", "frameworks", "seniority", "skill_weights"));

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema,
                        "temperature", 0));

        JsonNode root = objectMapper.readTree(geminiClient.generateContent(key, body));
        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        return RoleProfileRules.fromGemini(objectMapper.readTree(text), job);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static boolean isHrEdited(JobRoleProfile profile) {
        return profile != null && profile.getSource() == ProfileSource.HR;
    }

    private static String experience(Job job) {
        Integer min = job.getExperienceMinYears();
        Integer max = job.getExperienceMaxYears();
        if (min == null && max == null) return "not stated";
        if (max == null) return min + "+ years";
        return (min == null ? 0 : min) + "-" + max + " years";
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return RoleProfileRules.parseEnum(type, value, field);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown " + field + ": " + value + ".", "invalid_" + field);
        }
    }

    private static Map<String, Integer> validatedWeights(Map<String, Integer> weights) {
        if (weights.size() > RoleProfileRules.MAX_SKILLS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "At most " + RoleProfileRules.MAX_SKILLS + " skills can be weighted.", "invalid_skill_weights");
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        weights.forEach((skill, weight) -> {
            String key = skill == null ? "" : skill.toLowerCase(Locale.ROOT).trim();
            if (key.isEmpty() || weight == null || weight < 1 || weight > 5) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Each skill needs a name and a weight from 1 to 5.", "invalid_skill_weights");
            }
            out.put(key, weight);
        });
        return out;
    }

    private static RoleProfileResponse toResponse(JobRoleProfile p) {
        return new RoleProfileResponse(
                p.getRoleFamily().name(),
                Boolean.TRUE.equals(p.getIsTechnical()),
                p.getLanguages() == null ? List.of() : p.getLanguages(),
                p.getFrameworks() == null ? List.of() : p.getFrameworks(),
                p.getSeniority().name(),
                p.getSkillWeights() == null ? Map.of() : p.getSkillWeights(),
                p.getSource().name(),
                p.getEditedBy() == null ? null : p.getEditedBy().toString(),
                p.getUpdatedAt());
    }
}
