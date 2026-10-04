package com.smartstaff.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F17: the emails the backend sends, their default text and placeholder
 *  rendering. Plain text only. Reminders and rejections carry no link: only a
 *  token's hash is stored, so the original link can't be rebuilt. */
public final class EmailTemplates {

    public enum Kind {
        ASSESSMENT_INVITE(true, "candidate_name", "role", "link", "expires_at"),
        INTERVIEW_INVITE(true, "candidate_name", "role", "link", "expires_at"),
        REMINDER(false, "candidate_name", "role", "expires_at"),
        REJECTION(false, "candidate_name", "role");

        private final boolean invite;
        private final Set<String> placeholders;

        Kind(boolean invite, String... placeholders) {
            this.invite = invite;
            this.placeholders = new LinkedHashSet<>(List.of(placeholders));
        }

        /** Invites must contain {link}. */
        public boolean invite() {
            return invite;
        }

        public Set<String> placeholders() {
            return placeholders;
        }

        public static Kind parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return null;
            }
        }
    }

    public record Template(String subject, String body) {}

    public static final int MAX_SUBJECT = 200;
    public static final int MAX_BODY = 10_000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_]+)}");
    private static final String SIGN_OFF = "\n\nBest regards,\nTalent Acquisition Team";

    private EmailTemplates() {}

    public static Template defaults(Kind kind) {
        return switch (kind) {
            case ASSESSMENT_INVITE -> new Template("Your assessment for {role}",
                    "Hi {candidate_name},\n\nThank you for applying for the {role} role. Please take the online assessment "
                            + "using your personal link below:\n\n{link}\n\nThe link works once and expires on {expires_at}. "
                            + "Use a laptop or desktop with Chrome or Edge, somewhere quiet." + SIGN_OFF);
            case INTERVIEW_INVITE -> new Template("Your first-round interview for {role}",
                    "Hi {candidate_name},\n\nYour short AI-conducted first-round interview for the {role} role is ready. "
                            + "Open the link below in Chrome or Edge from a quiet place and allow microphone access when "
                            + "asked:\n\n{link}\n\nThe link works once and expires on {expires_at}. The interview takes about "
                            + "10–15 minutes." + SIGN_OFF);
            case REMINDER -> new Template("Reminder: your {role} link expires soon",
                    "Hi {candidate_name},\n\nA quick reminder that the link we sent you for the {role} role expires on "
                            + "{expires_at}. Please use the link in our earlier email before then." + SIGN_OFF);
            case REJECTION -> new Template("Your application for {role}",
                    "Hi {candidate_name},\n\nThank you for your interest in the {role} role and for the time you spent with "
                            + "us. After careful consideration we have decided not to move forward with your application.\n\n"
                            + "We wish you every success." + SIGN_OFF);
        };
    }

    /** Null when the template is acceptable, else what's wrong. */
    public static String problem(Kind kind, String subject, String body) {
        if (subject == null || subject.isBlank()) return "subject is required.";
        if (subject.length() > MAX_SUBJECT) return "subject must be at most " + MAX_SUBJECT + " characters.";
        if (subject.contains("\n") || subject.contains("\r")) return "subject must be a single line.";
        if (body == null || body.isBlank()) return "body is required.";
        if (body.length() > MAX_BODY) return "body must be at most " + MAX_BODY + " characters.";
        Set<String> used = new LinkedHashSet<>(placeholders(subject));
        used.addAll(placeholders(body));
        for (String p : used) {
            if (!kind.placeholders().contains(p)) {
                return "{" + p + "} can't be used here. Available: "
                        + kind.placeholders().stream().map(x -> "{" + x + "}").reduce((a, b) -> a + ", " + b).orElse("none") + ".";
            }
        }
        if (kind.invite() && !placeholders(body).contains("link")) return "The body must contain {link}.";
        return null;
    }

    private static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Fills the known placeholders; values are made single-line when singleLine (subjects). */
    public static String render(String text, Map<String, String> values, boolean singleLine) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            if (singleLine) v = v.replaceAll("[\\r\\n]+", " ");
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
