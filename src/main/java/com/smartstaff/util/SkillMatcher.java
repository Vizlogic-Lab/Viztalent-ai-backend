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
        for (int i = 0; i < words.length - 1; i++) {
            sb.append(Pattern.quote(words[i])).append("[\\s\\-/]+");
        }
        // The last word matches in singular or plural, whichever way the JD or CV wrote it
        // ("escalations" ~ "escalation", "dependency" ~ "dependencies"), but not a longer word.
        sb.append(singularOrPlural(words[words.length - 1].toLowerCase(Locale.ROOT)));
        sb.append("(?![\\w+#])");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    static String singularOrPlural(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return Pattern.quote(w.substring(0, w.length() - 3)) + "(?:y|ies)";
        }
        if (w.length() > 3 && w.endsWith("y") && !isVowel(w.charAt(w.length() - 2))) {
            return Pattern.quote(w.substring(0, w.length() - 1)) + "(?:y|ies)";
        }
        // Strip a plain plural "s", but leave words that just end in s ("analysis", "process", "aws").
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("is") && !w.endsWith("us")) {
            String base = w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes"))
                    ? w.substring(0, w.length() - 2) : w.substring(0, w.length() - 1);
            return Pattern.quote(base) + "(?:s|es)?";
        }
        return Pattern.quote(w) + "(?:s|es)?";
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }
}
