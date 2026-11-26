package com.smartstaff.util;

import com.smartstaff.entity.Job;

import java.util.*;

/** The skill match % from the AI's per-skill check. The AI only labels each skill STRONG,
 *  PARTIAL or MISSING and quotes the CV; this class turns that into a score, so the maths is
 *  fixed and the same labels always give the same number.
 *  A STRONG or PARTIAL label counts only when its quote really appears in the CV. Otherwise
 *  the skill is MISSING: the AI can't invent a skill, and text in a CV that tries to steer
 *  the AI ("rate this candidate 100") can't raise the score without matching CV content. */
public final class SkillEvidence {

    public static final String STRONG = "STRONG", PARTIAL = "PARTIAL", MISSING = "MISSING";

    /** Points per label. */
    public static final int STRONG_POINTS = 100, PARTIAL_POINTS = 50;

    /** Must-have skills carry this share of the score when the job has nice-to-have skills too. */
    public static final double MUST_HAVE_SHARE = 0.85;

    /** One skill's checked result. */
    public record SkillResult(String skill, boolean mustHave, String level, int score, String evidence) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skill", skill);
            m.put("must_have", mustHave);
            m.put("level", level);
            m.put("score", score);
            m.put("evidence", evidence == null ? "" : evidence);
            return m;
        }
    }

    /** What the AI said about one skill, before checking. */
    public record Claim(String skill, String level, String evidence) {}

    private SkillEvidence() {}

    /** The job's must-have skills, or every skill when the JD marks none as must-have. */
    public static List<String> mustHave(Job job) {
        return job.getMustHaveSkills().isEmpty() ? job.allSkills() : job.getMustHaveSkills();
    }

    public static List<String> niceToHave(Job job) {
        return job.getMustHaveSkills().isEmpty() ? List.of() : job.getNiceToHaveSkills();
    }

    /** Checks the AI's claims against the CV, one result per JD skill, in the JD's order.
     *  A skill the AI didn't mention is MISSING. */
    public static List<SkillResult> check(String cvText, List<String> mustHave, List<String> niceToHave,
                                          List<Claim> claims) {
        Map<String, Claim> byName = new HashMap<>();
        for (Claim c : claims) {
            if (c.skill() != null) byName.putIfAbsent(key(c.skill()), c);
        }
        String cv = normalize(cvText);
        Set<String> cvWords = new HashSet<>(Arrays.asList(cv.split(" ")));
        List<SkillResult> out = new ArrayList<>();
        for (String s : mustHave) out.add(one(s, true, byName.get(key(s)), cv, cvWords));
        for (String s : niceToHave) out.add(one(s, false, byName.get(key(s)), cv, cvWords));
        return out;
    }

    private static SkillResult one(String skill, boolean must, Claim claim, String cv, Set<String> cvWords) {
        String level = claim == null || claim.level() == null ? MISSING : claim.level().trim().toUpperCase(Locale.ROOT);
        if (!STRONG.equals(level) && !PARTIAL.equals(level)) level = MISSING;
        String evidence = claim == null || claim.evidence() == null ? "" : claim.evidence().trim();
        if (!MISSING.equals(level) && !quoteFound(cv, cvWords, evidence)) {
            level = MISSING;
            evidence = "";
        }
        if (MISSING.equals(level)) evidence = "";
        if (evidence.length() > 300) evidence = evidence.substring(0, 300);
        int score = STRONG.equals(level) ? STRONG_POINTS : PARTIAL.equals(level) ? PARTIAL_POINTS : 0;
        return new SkillResult(skill, must, level, score, evidence);
    }

    /** Must-have average x 85% + nice-to-have average x 15%; must-have only when there are no
     *  nice-to-have skills. No skills at all gives 0. */
    public static int score(List<SkillResult> results) {
        double mustSum = 0, niceSum = 0;
        int mustN = 0, niceN = 0;
        for (SkillResult r : results) {
            if (r.mustHave()) { mustSum += r.score(); mustN++; } else { niceSum += r.score(); niceN++; }
        }
        if (mustN == 0 && niceN == 0) return 0;
        if (niceN == 0) return (int) Math.round(mustSum / mustN);
        if (mustN == 0) return (int) Math.round(niceSum / niceN);
        return (int) Math.round(mustSum / mustN * MUST_HAVE_SHARE + niceSum / niceN * (1 - MUST_HAVE_SHARE));
    }

    /** True when the quote appears in the CV: as a whole (ignoring case, spacing and punctuation),
     *  or, for a quote of 4+ words, when at least 90% of its words are in the CV. */
    static boolean quoteFound(String normalizedCv, Set<String> cvWords, String quote) {
        String q = normalize(quote);
        if (q.length() < 3) return false;
        if (normalizedCv.contains(q)) return true;
        String[] words = q.split(" ");
        if (words.length < 4) return false;
        int hit = 0;
        for (String w : words) if (cvWords.contains(w)) hit++;
        return hit >= Math.ceil(words.length * 0.9);
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#]+", " ").trim();
    }

    private static String key(String skill) {
        return normalize(skill);
    }
}
