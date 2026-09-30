package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.request.RoleProfileRequest;
import com.smartstaff.dto.response.RoleProfileResponse;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;
import com.smartstaff.entity.JobRoleProfile.ProfileSource;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.JobRoleProfileRepository;
import com.smartstaff.service.RoleProfileService;
import com.smartstaff.util.SkillDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Role profile extraction and management.
 * Tries Gemini first, falls back to rule-based extraction if Gemini fails.
 */
@Service
@Slf4j
public class RoleProfileServiceImpl implements RoleProfileService {

    private final JobRepository jobRepository;
    private final JobRoleProfileRepository roleProfileRepository;
    private final SkillDictionary skillDictionary;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.gemini.api-key:}")
    private String geminiApiKey;

    public RoleProfileServiceImpl(
            JobRepository jobRepository,
            JobRoleProfileRepository roleProfileRepository,
            SkillDictionary skillDictionary,
            ObjectMapper objectMapper,
            RestTemplate restTemplate) {
        this.jobRepository = jobRepository;
        this.roleProfileRepository = roleProfileRepository;
        this.skillDictionary = skillDictionary;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    @Async("profileExtractorExecutor")
    @Transactional
    public void extractRoleProfile(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));

        // Delete existing profile if any (re-extraction)
        roleProfileRepository.deleteById(jobId);

        try {
            // Try Gemini extraction
            JobRoleProfile profile = extractViaGemini(job);
            roleProfileRepository.save(profile);
            log.info("Extracted role profile for job {} via Gemini", jobId);
        } catch (Exception e) {
            log.warn("Gemini extraction failed for job {}, using fallback", jobId, e);
            try {
                // Fallback: rule-based extraction
                JobRoleProfile profile = extractViaFallback(job);
                roleProfileRepository.save(profile);
                log.info("Extracted role profile for job {} via fallback", jobId);
            } catch (Exception fallbackError) {
                log.error("Both Gemini and fallback extraction failed for job {}", jobId, fallbackError);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RoleProfileResponse getRoleProfile(UUID jobId) {
        JobRoleProfile profile = getRoleProfileEntity(jobId);
        return mapToResponse(profile);
    }

    @Override
    @Transactional
    public void updateRoleProfile(UUID jobId, RoleProfileRequest request, UUID editorId) {
        JobRoleProfile profile = getRoleProfileEntity(jobId);

        // Update allowed fields
        if (request.roleFamily() != null) {
            profile.setRoleFamily(RoleFamily.valueOf(request.roleFamily()));
        }
        if (request.isTechnical() != null) {
            profile.setIsTechnical(request.isTechnical());
        }
        if (request.languages() != null) {
            profile.setLanguages(request.languages());
        }
        if (request.frameworks() != null) {
            profile.setFrameworks(request.frameworks());
        }
        if (request.seniority() != null) {
            profile.setSeniority(SeniorityLevel.valueOf(request.seniority()));
        }
        if (request.skillWeights() != null) {
            // Validate weights are 1-5
            validateSkillWeights(request.skillWeights());
            profile.setSkillWeights(request.skillWeights());
        }

        profile.setSource(ProfileSource.HR);
        profile.setEditedBy(editorId);
        profile.setUpdatedAt(Instant.now());

        roleProfileRepository.save(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public JobRoleProfile getRoleProfileEntity(UUID jobId) {
        return roleProfileRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Role profile not found for this job."));
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private JobRoleProfile extractViaGemini(Job job) throws Exception {
        if (geminiApiKey.isBlank()) {
            throw new Exception("Gemini API key not configured");
        }

        // Prepare input: job title, skills, experience, first 4000 chars of text
        String jdText = job.getJdText();
        if (jdText == null) jdText = "";
        String jdSummary = jdText.substring(0, Math.min(4000, jdText.length()));

        String allSkills = String.join(", ",
                job.getMustHaveSkills().stream().limit(20).toList());

        String prompt = String.format("""
                Analyze this job posting and extract the role profile.

                Job Title: %s
                Skills Required: %s
                Experience: %d-%d years
                Job Description (first 4000 chars): %s

                Return a JSON object with:
                {
                  "roleFamily": "BACKEND|FRONTEND|FULLSTACK|DATA|DEVOPS|QA|MOBILE|NON_TECHNICAL",
                  "isTechnical": boolean,
                  "languages": ["Python", "Java", ...],
                  "frameworks": ["Spring", "Django", ...],
                  "seniority": "JUNIOR|MID|SENIOR|LEAD",
                  "skillWeights": {"python": 5, "spring": 4, ...}
                }

                Skill weights must be 1-5. Only include skills that appear in the job description.
                """,
                job.getTitle(),
                allSkills,
                job.getExperienceMinYears() == null ? 0 : job.getExperienceMinYears(),
                job.getExperienceMaxYears() == null ? 10 : job.getExperienceMaxYears(),
                jdSummary);

        // Call Gemini (via your existing GeminiClient)
        // For now, mock this as it requires actual Gemini integration
        // In real implementation, use: geminiClient.generateContent(prompt, ...)

        throw new UnsupportedOperationException("Gemini integration deferred; use fallback");
    }

    private JobRoleProfile extractViaFallback(Job job) {
        List<String> allSkills = new ArrayList<>(job.getMustHaveSkills());
        allSkills.addAll(job.getNiceToHaveSkills());

        // Rule table: skill -> (role_family, weight)
        Map<String, Integer> skillWeights = new HashMap<>();
        RoleFamily detectedRole = RoleFamily.NON_TECHNICAL;
        boolean isTechnical = !allSkills.isEmpty();

        for (String skill : allSkills) {
            String lower = skill.toLowerCase();

            // Backend
            if (lower.contains("java") || lower.contains("spring") || lower.contains("python")
                    || lower.contains("node") || lower.contains("golang") || lower.contains("rust")) {
                skillWeights.putIfAbsent(skill, 4);
                if (detectedRole == RoleFamily.NON_TECHNICAL) {
                    detectedRole = RoleFamily.BACKEND;
                }
            }
            // Frontend
            if (lower.contains("react") || lower.contains("vue") || lower.contains("angular")
                    || lower.contains("typescript") || lower.contains("css")) {
                skillWeights.putIfAbsent(skill, 4);
                if (detectedRole == RoleFamily.BACKEND) {
                    detectedRole = RoleFamily.FULLSTACK;
                } else {
                    detectedRole = RoleFamily.FRONTEND;
                }
            }
            // Data
            if (lower.contains("sql") || lower.contains("spark") || lower.contains("pandas")
                    || lower.contains("analytics") || lower.contains("warehouse")) {
                skillWeights.putIfAbsent(skill, 4);
                detectedRole = RoleFamily.DATA;
            }
            // DevOps
            if (lower.contains("docker") || lower.contains("kubernetes") || lower.contains("aws")
                    || lower.contains("terraform") || lower.contains("ci/cd")) {
                skillWeights.putIfAbsent(skill, 4);
                detectedRole = RoleFamily.DEVOPS;
            }
            // QA
            if (lower.contains("test") || lower.contains("selenium") || lower.contains("cypress")
                    || lower.contains("automation")) {
                skillWeights.putIfAbsent(skill, 4);
                detectedRole = RoleFamily.QA;
            }

            // Default weight for other skills
            skillWeights.putIfAbsent(skill, 2);
        }

        // Clamp weights to 1-5
        skillWeights.replaceAll((k, v) -> Math.max(1, Math.min(5, v)));

        // Detect seniority from experience
        Integer minYears = job.getExperienceMinYears() == null ? 0 : job.getExperienceMinYears();
        SeniorityLevel seniority = minYears >= 10 ? SeniorityLevel.LEAD
                : minYears >= 5 ? SeniorityLevel.SENIOR
                : minYears >= 2 ? SeniorityLevel.MID
                : SeniorityLevel.JUNIOR;

        JobRoleProfile profile = new JobRoleProfile();
        profile.setJobId(job.getId());
        profile.setRoleFamily(detectedRole);
        profile.setIsTechnical(isTechnical);
        profile.setLanguages(List.of("Python", "Java", "TypeScript")); // Defaults; can refine
        profile.setFrameworks(List.of()); // Empty for fallback
        profile.setSeniority(seniority);
        profile.setSkillWeights(skillWeights);
        profile.setSource(ProfileSource.AI_FALLBACK);
        profile.setExtractedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());

        return profile;
    }

    private void validateSkillWeights(Map<String, Integer> weights) {
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (entry.getValue() < 1 || entry.getValue() > 5) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Skill weights must be between 1 and 5. Invalid: " + entry.getKey());
            }
        }
    }

    private RoleProfileResponse mapToResponse(JobRoleProfile profile) {
        return new RoleProfileResponse(
                profile.getRoleFamily().name(),
                profile.getIsTechnical(),
                profile.getLanguages(),
                profile.getFrameworks(),
                profile.getSeniority().name(),
                profile.getSkillWeights(),
                profile.getSource().name()
        );
    }
}
