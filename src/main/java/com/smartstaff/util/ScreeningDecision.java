package com.smartstaff.util;

import com.smartstaff.entity.Candidate;

/** Final screening decision for a candidate.
 *  Step 1: years of experience must sit inside the JD range (UNKNOWN goes on to step 2).
 *  Step 2: skill match % (the AI per-skill check, or the keyword check when AI is unavailable)
 *  and/or the overall AI match, per the admin's pass rule. While the AI check runs the
 *  candidate is PENDING, because the skill match % is not final yet.
 *  A recruiter override (with a reason) passes the candidate whatever the steps said. */
public final class ScreeningDecision {

    public static final int PASS_MARK = 50;
    /** A step-2 fail whose deciding score is this close below the pass mark (45-49) becomes
     *  REVIEW: the AI match moves a few points between runs, so a recruiter should decide. */
    public static final int REVIEW_MARGIN = 5;

    /** Admin-chosen step-2 rule. */
    public enum Rule {
        EITHER, BOTH, AI_ONLY, KEYWORD_ONLY;

        public static Rule parse(String v) {
            try { return v == null ? EITHER : Rule.valueOf(v.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return EITHER; }
        }
    }

    public record Result(String decision, String step, String reason) {}

    private ScreeningDecision() {}

    /** Step 1 status for a candidate's years against the job's range. */
    public static String experienceStatus(int years, Integer min, Integer max) {
        if (years <= 0) return "UNKNOWN";
        if (min != null && years < min) return "FAIL";
        if (max != null && max > 0 && years > max) return "FAIL";
        return "PASS";
    }

    /** PASS, FAIL, PENDING or REVIEW (a near miss at step 2, for a recruiter to decide). */
    public static Result decide(Candidate c, Rule rule) {
        Result r = decideStrict(c, rule);
        if (!"FAIL".equals(r.decision()) || !"SKILLS_AI".equals(r.step())) return r;
        Integer near = decidingScore(c, rule);
        if (near != null && near >= PASS_MARK - REVIEW_MARGIN && near < PASS_MARK) {
            return new Result("REVIEW", "SKILLS_AI", r.reason() + " — close to " + PASS_MARK + ", needs a recruiter's review");
        }
        return r;
    }

    /** The score that would have to reach the pass mark for the candidate to pass. */
    private static Integer decidingScore(Candidate c, Rule rule) {
        int kw = c.getFitScore();
        Integer ai = "DONE".equals(c.getAiMatchStatus()) ? c.getAiMatchScore() : null;
        return switch (rule) {
            case KEYWORD_ONLY -> kw;
            case AI_ONLY -> ai != null ? ai : kw;
            case BOTH -> ai == null ? kw : Math.min(kw, ai);
            case EITHER -> ai == null ? kw : Math.max(kw, ai);
        };
    }

    private static Result decideStrict(Candidate c, Rule rule) {
        if (c.isOverridePassed()) {
            return new Result("PASS", "OVERRIDE", "Passed by recruiter: " + c.getOverrideReason());
        }
        if ("FAIL".equals(c.getExperienceStatus())) {
            return new Result("FAIL", "EXPERIENCE", c.getYearsExperience() + " yrs is outside the JD experience range");
        }
        int kw = c.getFitScore();
        boolean kwPass = kw >= PASS_MARK;
        String ai = c.getAiMatchStatus();
        boolean aiDone = "DONE".equals(ai) && c.getAiMatchScore() != null;
        boolean aiPending = "PENDING".equals(ai);
        boolean aiPass = aiDone && c.getAiMatchScore() >= PASS_MARK;
        // With no AI result possible (no key / failed), the keyword score decides.
        boolean aiUnavailable = !aiDone && !aiPending;
        // The AI check also produces the final skill match %, so nothing is decided until it's done.
        if (aiPending) return pending();

        switch (rule) {
            case KEYWORD_ONLY:
                return kwPass ? pass("Skill match " + kw) : fail("Skill match " + kw + " is below " + PASS_MARK);
            case AI_ONLY:
                if (aiPending) return pending();
                if (aiUnavailable) {
                    return kwPass ? pass("AI match unavailable; skill match " + kw)
                                  : fail("AI match unavailable; skill match " + kw + " is below " + PASS_MARK);
                }
                return aiPass ? pass("AI match " + c.getAiMatchScore())
                              : fail("AI match " + c.getAiMatchScore() + " is below " + PASS_MARK);
            case BOTH:
                if (!kwPass) return fail("Skill match " + kw + " is below " + PASS_MARK);
                if (aiPending) return pending();
                if (aiUnavailable) return pass("Skill match " + kw + "; AI match unavailable");
                return aiPass ? pass("Skill match and AI match are both " + PASS_MARK + "+")
                              : fail("AI match " + c.getAiMatchScore() + " is below " + PASS_MARK);
            case EITHER:
            default:
                if (kwPass) return pass("Skill match " + kw);
                if (aiPass) return pass("AI match " + c.getAiMatchScore());
                if (aiPending) return pending();
                return fail("Skill match and AI match are below " + PASS_MARK);
        }
    }

    private static Result pass(String why) { return new Result("PASS", "SKILLS_AI", why); }
    private static Result fail(String why) { return new Result("FAIL", "SKILLS_AI", why); }
    private static Result pending() { return new Result("PENDING", "SKILLS_AI", "Waiting for the AI skill check"); }
}
