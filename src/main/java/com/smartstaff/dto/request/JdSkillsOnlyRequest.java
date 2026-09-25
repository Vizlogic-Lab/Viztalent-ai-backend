package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/upload_jd_skills — see VoiceScreening.jsx's submitSkillsOnly:
 *  {skills: "comma or newline separated string", title}. */
public record JdSkillsOnlyRequest(
        @NotBlank String skills,
        String title
) {}
