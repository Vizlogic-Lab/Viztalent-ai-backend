package com.smartstaff.service.impl;

import com.smartstaff.entity.AppSetting;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AppSettingRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** The admin-editable prompt that writes personal assessments (setting
 *  assessment_prompt). Unset means the built-in default in
 *  resources/prompts/assessment-default.txt. The prompt must keep both
 *  placeholders, which are replaced with the JD text and the resume text. */
@Service
public class AssessmentPromptService {

    public static final String KEY = "assessment_prompt";
    public static final String JD_PLACEHOLDER = "{{INSERT_JOB_DESCRIPTION}}";
    public static final String RESUME_PLACEHOLDER = "{{INSERT_CANDIDATE_RESUME}}";
    static final int MAX_LENGTH = 20_000;

    private final AppSettingRepository settings;
    private final String defaultPrompt;

    public AssessmentPromptService(AppSettingRepository settings) {
        this.settings = settings;
        try {
            this.defaultPrompt = new ClassPathResource("prompts/assessment-default.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompts/assessment-default.txt", e);
        }
    }

    public record PromptView(String prompt, boolean is_default, String default_prompt,
                             String updated_by, Instant updated_at) {}

    public record PromptRequest(String prompt) {}

    @Transactional(readOnly = true)
    public PromptView view() {
        return settings.findById(KEY)
                .filter(s -> s.getValue() != null && !s.getValue().isBlank())
                .map(s -> new PromptView(s.getValue(), false, defaultPrompt, s.getUpdatedBy(), s.getUpdatedAt()))
                .orElse(new PromptView(defaultPrompt, true, defaultPrompt, null, null));
    }

    /** The prompt in force: the saved one, else the default. */
    @Transactional(readOnly = true)
    public String current() {
        return view().prompt();
    }

    @Transactional
    public PromptView save(PromptRequest req, User admin) {
        String prompt = req == null || req.prompt() == null ? "" : req.prompt().strip();
        String problem = validate(prompt);
        if (problem != null) throw new ApiException(HttpStatus.BAD_REQUEST, problem, "invalid_prompt");
        AppSetting s = settings.findById(KEY).orElse(new AppSetting());
        s.setKey(KEY);
        s.setValue(prompt);
        s.setUpdatedBy(admin == null ? null : admin.publicId());
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        return view();
    }

    /** Back to the built-in default. */
    @Transactional
    public PromptView reset() {
        settings.deleteById(KEY);
        return view();
    }

    /** Fills the placeholders. */
    public static String render(String prompt, String jdText, String resumeText) {
        return prompt.replace(JD_PLACEHOLDER, jdText == null ? "" : jdText)
                .replace(RESUME_PLACEHOLDER, resumeText == null ? "" : resumeText);
    }

    static String validate(String prompt) {
        if (prompt.isBlank()) return "The prompt can't be empty.";
        if (prompt.length() > MAX_LENGTH) return "The prompt is too long (max " + MAX_LENGTH + " characters).";
        if (!prompt.contains(JD_PLACEHOLDER)) return "The prompt must contain " + JD_PLACEHOLDER + ".";
        if (!prompt.contains(RESUME_PLACEHOLDER)) return "The prompt must contain " + RESUME_PLACEHOLDER + ".";
        return null;
    }
}
