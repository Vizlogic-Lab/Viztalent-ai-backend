package com.smartstaff.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Does a CV show a JD skill? True when the CV contains the skill name or one of its aliases
 *  as a whole word/phrase (case-insensitive; "-", "/" and extra spaces between words are
 *  tolerated, and a trailing plural "s" is allowed). Deterministic — no AI per CV. */
public final class SkillMatcher {

    private SkillMatcher() {}

    public static boolean matches(String cvText, String skill, Map<String, List<String>> aliases) {
        if (cvText == null || cvText.isBlank() || skill == null || skill.isBlank()) return false;
        if (pattern(skill).matcher(cvText).find()) return true;
        List<String> al = aliases == null ? null : aliases.get(skill.toLowerCase(Locale.ROOT));
        if (al == null) return false;
        for (String a : al) {
            if (a != null && !a.isBlank() && pattern(a).matcher(cvText).find()) return true;
        }
        return false;
    }

    static Pattern pattern(String phrase) {
        String[] words = phrase.strip().split("[\\s\\-/]+");
        StringBuilder sb = new StringBuilder("(?<![\\w+#.])");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append("[\\s\\-/]+");
            sb.append(Pattern.quote(words[i]));
        }
        // Allow a plural on the last word ("stakeholders"), but not a longer word.
        sb.append("(?:s|es)?(?![\\w+#])");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
