package com.smartstaff.util;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic (no-LLM) dictionary match of JD/resume text against a
 *  curated skill list — same approach as the sibling Java backend's
 *  resume-processor: word-boundary regex so "C" doesn't match inside
 *  "cooking" but "C++"/"C#" still match. */
@Component
public class SkillDictionary {

    private final List<String> canonicalSkills = new ArrayList<>();
    private final Map<String, Pattern> patterns = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try (var in = new ClassPathResource("skills.txt").getInputStream();
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String skill = line.strip();
                if (skill.isEmpty() || skill.startsWith("#")) continue;
                canonicalSkills.add(skill);
                patterns.put(skill, boundaryPattern(skill));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not load skills.txt", e);
        }
    }

    /** Word-boundary-ish match that still works for skills containing
     *  symbols (C++, C#, CI/CD, .NET) where \b doesn't behave sensibly. */
    private static Pattern boundaryPattern(String skill) {
        String escaped = Pattern.quote(skill);
        return Pattern.compile("(?<![\\w+#.])" + escaped + "(?![\\w+#])", Pattern.CASE_INSENSITIVE);
    }

    /** Returns the canonical (lowercase, as-listed) skills found in the text,
     *  in dictionary order, deduplicated. */
    public List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> found = new ArrayList<>();
        for (String skill : canonicalSkills) {
            if (patterns.get(skill).matcher(text).find()) {
                found.add(skill);
            }
        }
        return found;
    }

    public List<String> all() {
        return Collections.unmodifiableList(canonicalSkills);
    }

    private static final List<String> OPTIONAL_PHRASES = List.of(
            "nice to have", "preferred", "good to have", "bonus", "a plus", "is a plus", "plus point");

    // Sentence/clause boundary — a fixed character window bleeds a qualifier
    // phrase from one sentence into a skill mentioned in an unrelated,
    // adjacent one (e.g. "Required: Docker, Linux. X is a plus." must not
    // mark Linux as optional because of "a plus" two sentences later).
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[.\\n;]");

    /** Same as extract(), but also classifies each match as must-have vs
     *  nice-to-have based on wording in the same sentence/clause — a
     *  deterministic heuristic (no LLM). The qualifier can lead ("Preferred:
     *  Kubernetes") or trail ("Kubernetes experience is a plus") the skill
     *  mention. Defaults to must-have. */
    public Classified classify(String text) {
        if (text == null || text.isBlank()) return new Classified(List.of(), List.of());
        List<String> mustHave = new ArrayList<>();
        List<String> niceToHave = new ArrayList<>();
        for (String skill : canonicalSkills) {
            var matcher = patterns.get(skill).matcher(text);
            if (!matcher.find()) continue;

            int clauseStart = lastBoundaryBefore(text, matcher.start());
            int clauseEnd = firstBoundaryAfter(text, matcher.end());
            String clause = text.substring(clauseStart, clauseEnd).toLowerCase();

            boolean optional = OPTIONAL_PHRASES.stream().anyMatch(clause::contains);
            (optional ? niceToHave : mustHave).add(skill);
        }
        return new Classified(mustHave, niceToHave);
    }

    private static int lastBoundaryBefore(String text, int index) {
        var m = CLAUSE_BOUNDARY.matcher(text.substring(0, index));
        int last = 0;
        while (m.find()) last = m.end();
        return last;
    }

    private static int firstBoundaryAfter(String text, int index) {
        var m = CLAUSE_BOUNDARY.matcher(text);
        return m.find(index) ? m.start() : text.length();
    }

    public record Classified(List<String> mustHave, List<String> niceToHave) {}
}
