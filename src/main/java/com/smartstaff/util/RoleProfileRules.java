package com.smartstaff.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.ProfileSource;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Pure logic behind role profiles: validating Gemini's JSON and the
 *  rule-based fallback used when Gemini is missing or fails. */
public final class RoleProfileRules {

    public static final int MAX_SKILLS = 25;

    private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("python", "python"), Map.entry("python3", "python"),
            Map.entry("javascript", "javascript"), Map.entry("js", "javascript"), Map.entry("node", "javascript"),
            Map.entry("nodejs", "javascript"), Map.entry("node.js", "javascript"),
            Map.entry("typescript", "typescript"), Map.entry("ts", "typescript"),
            Map.entry("c++", "cpp"), Map.entry("cpp", "cpp"),
            Map.entry("c#", "csharp"), Map.entry("csharp", "csharp"),
            Map.entry("go", "go"), Map.entry("golang", "go"),
            Map.entry("kotlin", "kotlin"), Map.entry("swift", "swift"), Map.entry("rust", "rust"),
            Map.entry("php", "php"), Map.entry("ruby", "ruby"), Map.entry("scala", "scala"), Map.entry("sql", "sql"));

    private static final Map<RoleFamily, List<String>> FAMILY_KEYWORDS = new EnumMap<>(Map.of(
            RoleFamily.BACKEND, List.of("java", "spring", "python", "django", "flask", "fastapi", "node", "express",
                    "golang", "rust", "c#", ".net", "php", "microservices", "rest api", "hibernate", "kafka"),
            RoleFamily.FRONTEND, List.of("react", "angular", "vue", "html", "css", "javascript", "typescript",
                    "next.js", "redux", "tailwind"),
            RoleFamily.MOBILE, List.of("android", "ios", "kotlin", "swift", "flutter", "react native"),
            RoleFamily.DATA, List.of("sql", "spark", "pandas", "etl", "airflow", "tableau", "power bi",
                    "machine learning", "data warehouse", "hadoop", "snowflake"),
            RoleFamily.DEVOPS, List.of("docker", "kubernetes", "aws", "azure", "gcp", "terraform", "jenkins",
                    "ci/cd", "ansible", "linux"),
            RoleFamily.QA, List.of("selenium", "cypress", "testing", "qa", "test automation", "playwright", "appium")));

    /** Frameworks/libraries a coding question can meaningfully be about. */
    private static final List<String> CODE_KEYWORDS = List.of(
            "spring", "hibernate", "django", "flask", "fastapi", "express", "node", "react", "angular", "vue",
            "next.js", "redux", "pandas", "numpy", "spark", "sql", "kafka", "junit", "selenium", "cypress",
            "playwright", "android", "flutter", "react native", ".net", "rest api", "microservices", "jpa");

    private RoleProfileRules() {}

    /** A skill a coding question can be about: a programming language or a
     *  code framework (so "docker" or "excel" never get a CODE_WRITE slot). */
    public static boolean isCodeSkill(String skill) {
        String s = skill.toLowerCase(Locale.ROOT).trim();
        return LANGUAGE_ALIASES.containsKey(s) || CODE_KEYWORDS.stream().anyMatch(k -> containsWord(s, k));
    }

    /** Rule-based profile from the job's extracted skills and experience range. */
    public static JobRoleProfile fallback(Job job) {
        Map<RoleFamily, Integer> hits = new EnumMap<>(RoleFamily.class);
        for (String skill : job.allSkills()) {
            String s = skill.toLowerCase(Locale.ROOT);
            FAMILY_KEYWORDS.forEach((family, keywords) -> {
                if (keywords.stream().anyMatch(k -> containsWord(s, k))) hits.merge(family, 1, Integer::sum);
            });
        }

        RoleFamily family;
        if (hits.containsKey(RoleFamily.BACKEND) && hits.containsKey(RoleFamily.FRONTEND)) {
            family = RoleFamily.FULLSTACK;
        } else {
            family = hits.entrySet().stream()
                    .max(Map.Entry.<RoleFamily, Integer>comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(RoleFamily.NON_TECHNICAL);
        }

        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String s : job.getMustHaveSkills()) putWeight(weights, s, 4);
        for (String s : job.getNiceToHaveSkills()) putWeight(weights, s, 2);

        JobRoleProfile profile = base(job);
        profile.setRoleFamily(family);
        profile.setIsTechnical(family != RoleFamily.NON_TECHNICAL);
        profile.setLanguages(normaliseLanguages(job.allSkills(), false));
        profile.setFrameworks(List.of());
        profile.setSeniority(seniorityFor(job.getExperienceMinYears()));
        profile.setSkillWeights(weights);
        profile.setSource(ProfileSource.AI_FALLBACK);
        return profile;
    }

    /** Profile from Gemini's JSON. Throws IllegalArgumentException when a
     *  required field is missing or outside its enum, so the caller falls back. */
    public static JobRoleProfile fromGemini(JsonNode obj, Job job) {
        RoleFamily family = parseEnum(RoleFamily.class, obj.path("role_family").asText(null), "role_family");
        SeniorityLevel seniority = obj.hasNonNull("seniority")
                ? parseEnum(SeniorityLevel.class, obj.path("seniority").asText(), "seniority")
                : seniorityFor(job.getExperienceMinYears());
        if (!obj.path("is_technical").isBoolean()) throw new IllegalArgumentException("is_technical missing");

        Map<String, Integer> weights = new LinkedHashMap<>();
        for (JsonNode entry : obj.path("skill_weights")) {
            String skill = entry.path("skill").asText("").trim();
            if (skill.isEmpty() || !isGroundedInJob(skill, job)) continue;
            putWeight(weights, skill, clamp(entry.path("weight").asInt(3)));
        }
        if (weights.isEmpty()) {
            for (String s : job.getMustHaveSkills()) putWeight(weights, s, 4);
            for (String s : job.getNiceToHaveSkills()) putWeight(weights, s, 2);
        }

        JobRoleProfile profile = base(job);
        profile.setRoleFamily(family);
        profile.setIsTechnical(obj.path("is_technical").asBoolean() && family != RoleFamily.NON_TECHNICAL);
        profile.setLanguages(normaliseLanguages(texts(obj.path("languages")), true));
        profile.setFrameworks(dedupe(texts(obj.path("frameworks")), 15));
        profile.setSeniority(seniority);
        profile.setSkillWeights(weights);
        profile.setSource(ProfileSource.AI);
        return profile;
    }

    /** A skill Gemini names is kept only if it is one of the job's extracted
     *  skills or appears verbatim in the JD text — this keeps domain skills
     *  such as "DMS" or "SFA" while dropping invented ones. */
    public static boolean isGroundedInJob(String skill, Job job) {
        String s = skill.toLowerCase(Locale.ROOT).trim();
        for (String known : job.allSkills()) {
            if (known.toLowerCase(Locale.ROOT).trim().equals(s)) return true;
        }
        String text = job.getJdText();
        return text != null && containsWord(text, s);
    }

    /** Case-insensitive match not glued to other letters or digits, so "java"
     *  doesn't match inside "javascript" while "c++" still matches. */
    static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /** Lower-cased canonical names (c++ -> cpp, node -> javascript, golang -> go).
     *  With keepUnknown=false only recognised programming languages survive,
     *  which is what the skill-list fallback needs ("docker" isn't a language). */
    public static List<String> normaliseLanguages(Collection<String> raw, boolean keepUnknown) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String key = r.toLowerCase(Locale.ROOT).trim();
            String lang = LANGUAGE_ALIASES.getOrDefault(key, keepUnknown && !key.isEmpty() ? key : null);
            if (lang != null && !out.contains(lang) && out.size() < 10) out.add(lang);
        }
        return out;
    }

    public static SeniorityLevel seniorityFor(Integer minYears) {
        int y = minYears == null ? 0 : minYears;
        if (y >= 10) return SeniorityLevel.LEAD;
        if (y >= 5) return SeniorityLevel.SENIOR;
        if (y >= 2) return SeniorityLevel.MID;
        return SeniorityLevel.JUNIOR;
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " missing");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " has unknown value " + value);
        }
    }

    public static int clamp(int weight) {
        return Math.max(1, Math.min(5, weight));
    }

    private static JobRoleProfile base(Job job) {
        JobRoleProfile profile = new JobRoleProfile();
        profile.setJobId(job.getId());
        Instant now = Instant.now();
        profile.setExtractedAt(now);
        profile.setUpdatedAt(now);
        return profile;
    }

    private static void putWeight(Map<String, Integer> weights, String skill, int weight) {
        String key = skill.toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty() || weights.size() >= MAX_SKILLS) return;
        weights.merge(key, weight, Math::max);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            String t = n.asText("").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static List<String> dedupe(List<String> values, int max) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (out.size() >= max) break;
            if (out.stream().noneMatch(o -> o.equalsIgnoreCase(v))) out.add(v);
        }
        return out;
    }
}
