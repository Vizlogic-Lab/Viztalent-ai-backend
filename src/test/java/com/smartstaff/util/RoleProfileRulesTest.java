package com.smartstaff.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.ProfileSource;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleProfileRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Job job(List<String> must, List<String> nice, Integer minYears, String text) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setTitle("Role");
        job.setMustHaveSkills(must);
        job.setNiceToHaveSkills(nice);
        job.setExperienceMinYears(minYears);
        job.setJdText(text);
        return job;
    }

    @Test
    void fallbackRoleFamilies() {
        assertThat(RoleProfileRules.fallback(job(List.of("java", "spring boot"), List.of(), 3, "")).getRoleFamily())
                .isEqualTo(RoleFamily.BACKEND);
        assertThat(RoleProfileRules.fallback(job(List.of("react", "javascript"), List.of("css"), 1, "")).getRoleFamily())
                .as("'java' must not match inside 'javascript'").isEqualTo(RoleFamily.FRONTEND);
        assertThat(RoleProfileRules.fallback(job(List.of("java", "react"), List.of(), 3, "")).getRoleFamily())
                .isEqualTo(RoleFamily.FULLSTACK);
        assertThat(RoleProfileRules.fallback(job(List.of("docker", "kubernetes", "terraform"), List.of(), 3, "")).getRoleFamily())
                .isEqualTo(RoleFamily.DEVOPS);
        assertThat(RoleProfileRules.fallback(job(List.of("selenium", "cypress"), List.of(), 3, "")).getRoleFamily())
                .isEqualTo(RoleFamily.QA);
        assertThat(RoleProfileRules.fallback(job(List.of("kotlin", "android"), List.of(), 3, "")).getRoleFamily())
                .isEqualTo(RoleFamily.MOBILE);
    }

    @Test
    void fallbackForDomainSkillsIsNonTechnical() {
        JobRoleProfile p = RoleProfileRules.fallback(job(List.of("DMS", "SFA"), List.of("excel"), 4, ""));
        assertThat(p.getRoleFamily()).isEqualTo(RoleFamily.NON_TECHNICAL);
        assertThat(p.getIsTechnical()).isFalse();
        assertThat(p.getLanguages()).isEmpty();
        assertThat(p.getSkillWeights()).containsEntry("dms", 4).containsEntry("sfa", 4).containsEntry("excel", 2);
        assertThat(p.getSource()).isEqualTo(ProfileSource.AI_FALLBACK);
    }

    @Test
    void fallbackLanguagesOnlyRealLanguages() {
        JobRoleProfile p = RoleProfileRules.fallback(job(List.of("java", "docker", "c++", "node.js"), List.of("golang"), 3, ""));
        assertThat(p.getLanguages()).containsExactly("java", "cpp", "javascript", "go");
    }

    @Test
    void seniorityFromExperience() {
        assertThat(RoleProfileRules.seniorityFor(null)).isEqualTo(SeniorityLevel.JUNIOR);
        assertThat(RoleProfileRules.seniorityFor(1)).isEqualTo(SeniorityLevel.JUNIOR);
        assertThat(RoleProfileRules.seniorityFor(2)).isEqualTo(SeniorityLevel.MID);
        assertThat(RoleProfileRules.seniorityFor(5)).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(RoleProfileRules.seniorityFor(12)).isEqualTo(SeniorityLevel.LEAD);
    }

    @Test
    void geminiSkillsMustBeGroundedInTheJob() throws Exception {
        Job job = job(List.of("excel"), List.of(), 3, "Sales operations role. Must know DMS and SFA tools; C++ is a plus.");
        var obj = JSON.readTree("""
                {"role_family":"non_technical","is_technical":false,"languages":["C++"],"frameworks":[],
                 "seniority":"MID","skill_weights":[{"skill":"DMS","weight":9},{"skill":"sfa","weight":0},
                 {"skill":"Excel","weight":3},{"skill":"blockchain","weight":5},{"skill":"c++","weight":2},{"skill":"ms","weight":2}]}
                """);
        JobRoleProfile p = RoleProfileRules.fromGemini(obj, job);

        assertThat(p.getRoleFamily()).isEqualTo(RoleFamily.NON_TECHNICAL);
        assertThat(p.getSkillWeights()).containsOnlyKeys("dms", "sfa", "excel", "c++");
        assertThat(p.getSkillWeights()).containsEntry("dms", 5).containsEntry("sfa", 1);
        assertThat(p.getLanguages()).containsExactly("cpp");
        assertThat(p.getSource()).isEqualTo(ProfileSource.AI);
    }

    @Test
    void geminiInvalidEnumsAreRejected() throws Exception {
        Job job = job(List.of("java"), List.of(), 3, "");
        assertThatThrownBy(() -> RoleProfileRules.fromGemini(JSON.readTree(
                "{\"role_family\":\"WIZARD\",\"is_technical\":true,\"seniority\":\"MID\",\"skill_weights\":[]}"), job))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleProfileRules.fromGemini(JSON.readTree(
                "{\"role_family\":\"BACKEND\",\"is_technical\":true,\"seniority\":\"GURU\",\"skill_weights\":[]}"), job))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleProfileRules.fromGemini(JSON.readTree("{\"role_family\":\"BACKEND\"}"), job))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void geminiWithNoGroundedSkillsKeepsJobSkills() throws Exception {
        Job job = job(List.of("java"), List.of("docker"), 3, "");
        JobRoleProfile p = RoleProfileRules.fromGemini(JSON.readTree(
                "{\"role_family\":\"BACKEND\",\"is_technical\":true,\"seniority\":\"MID\",\"skill_weights\":[{\"skill\":\"cobol\",\"weight\":5}]}"), job);
        assertThat(p.getSkillWeights()).containsEntry("java", 4).containsEntry("docker", 2).doesNotContainKey("cobol");
    }
}
