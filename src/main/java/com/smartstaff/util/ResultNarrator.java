package com.smartstaff.util;

import com.smartstaff.entity.QuestionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F10 — factual text built from stored data only: per-question evidence lines,
 *  the template summary, and the number check that guards the AI summary. Pure. */
public final class ResultNarrator {

    private ResultNarrator() {}

    public record Question(int seq, QuestionType type, double earned, int points, List<Map<String, Object>> evidence,
                           List<String> flags) {}

    /** "Q7 (CODE_WRITE): 6/10 — passed 5 of 12 hidden tests; failed EDGE: wrong answer; LARGE: timed out". */
    public static List<String> evidenceLines(List<Question> questions) {
        List<String> lines = new ArrayList<>();
        for (Question q : questions) {
            StringBuilder sb = new StringBuilder("Q").append(q.seq()).append(" (").append(q.type().name()).append("): ")
                    .append(num(q.earned())).append('/').append(q.points());
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> e : q.evidence()) {
                Object kind = e.get("kind");
                if ("tests".equals(kind)) parts.add(tests(e));
                else if ("rule".equals(kind) || "objective".equals(kind) || "pending".equals(kind)) parts.add(stripDot(String.valueOf(e.get("text"))));
            }
            long criteria = q.evidence().stream().filter(e -> "criterion".equals(e.get("kind"))).count();
            if (criteria > 0) {
                long met = q.evidence().stream().filter(e -> "criterion".equals(e.get("kind")))
                        .filter(e -> e.get("score") instanceof Number n && n.doubleValue() > 0).count();
                parts.add("rubric: " + met + " of " + criteria + " criteria evidenced");
            }
            if (q.flags().contains("GRADER_DISAGREEMENT")) parts.add("graders disagreed (median of 3 runs)");
            if (!parts.isEmpty()) sb.append(" — ").append(String.join("; ", parts));
            lines.add(sb.toString());
        }
        return lines;
    }

    private static String tests(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder("passed ").append(e.get("hidden_passed")).append(" of ")
                .append(e.get("hidden_total")).append(" hidden tests");
        if (e.get("compile_error") != null) sb.append(" (compile error)");
        if (e.get("failed") instanceof List<?> failed && !failed.isEmpty()) {
            Map<String, Set<String>> byCategory = new LinkedHashMap<>();
            for (Object o : failed) {
                if (!(o instanceof Map<?, ?> f)) continue;
                byCategory.computeIfAbsent(String.valueOf(f.get("category")), k -> new LinkedHashSet<>())
                        .add(statusWords(String.valueOf(f.get("status"))));
            }
            List<String> fs = new ArrayList<>();
            byCategory.forEach((cat, st) -> fs.add(cat + ": " + String.join(", ", st)));
            sb.append("; failed ").append(String.join("; ", fs));
        }
        return sb.toString();
    }

    private static String statusWords(String status) {
        return switch (status) {
            case "TIMEOUT" -> "timed out";
            case "FAILED" -> "wrong answer";
            case "RUNTIME_ERROR" -> "runtime error";
            case "COMPILE_ERROR" -> "compile error";
            default -> status.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    // ── summary ─────────────────────────────────────────────────────────

    public record Facts(BigDecimal theory, BigDecimal handsOn, BigDecimal overall, String theoryBand, String handsOnBand,
                        String recommendation, List<String> flags, Map<String, BigDecimal> competencies,
                        List<String> evidenceLines, List<String> weakLines) {

        /** Everything the summary may draw on, as text (also what the number check reads). */
        public String asText() {
            StringBuilder sb = new StringBuilder();
            sb.append("All scores are out of 100.\n");
            sb.append("Theory score: ").append(num(theory)).append(" (").append(theoryBand).append(")\n");
            sb.append("Hands-on score: ").append(num(handsOn)).append(" (").append(handsOnBand).append(")\n");
            sb.append("Overall: ").append(num(overall)).append("\nRecommendation: ").append(recommendation).append('\n');
            if (!flags.isEmpty()) sb.append("Flags: ").append(String.join(", ", flags)).append('\n');
            competencies.forEach((c, v) -> sb.append("Competency ").append(c).append(": ").append(num(v)).append('\n'));
            sb.append("Evidence:\n");
            evidenceLines.forEach(l -> sb.append("- ").append(l).append('\n'));
            return sb.toString();
        }
    }

    public static Map<String, Object> template(Facts f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("theory_line", f.theory() == null ? "No theory questions in this level."
                : "Theoretical understanding: " + words(f.theoryBand()) + " (" + num(f.theory()) + "/100).");
        m.put("hands_on_line", f.handsOn() == null ? "No hands-on questions in this level."
                : "Hands-on capability: " + words(f.handsOnBand()) + " (" + num(f.handsOn()) + "/100).");
        m.put("gaps", f.weakLines().stream().limit(4).toList());
        m.put("source", "TEMPLATE");
        return m;
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** True when every number in `output` also appears in `input` (82 matches 82.00). */
    public static boolean numbersSupported(String output, String input) {
        Set<String> allowed = new HashSet<>();
        Matcher in = NUMBER.matcher(input);
        while (in.find()) allowed.add(norm(in.group()));
        Matcher out = NUMBER.matcher(output);
        while (out.find()) if (!allowed.contains(norm(out.group()))) return false;
        return true;
    }

    private static String norm(String n) {
        return new BigDecimal(n).stripTrailingZeros().toPlainString();
    }

    public static String num(BigDecimal v) {
        return v == null ? "n/a" : v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String num(double v) {
        return num(BigDecimal.valueOf(v));
    }

    private static String words(String band) {
        return band == null ? "n/a" : band.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
