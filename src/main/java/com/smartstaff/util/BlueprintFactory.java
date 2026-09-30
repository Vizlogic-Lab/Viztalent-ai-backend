package com.smartstaff.util;

import com.smartstaff.entity.Blueprint;
import com.smartstaff.entity.Blueprint.Slot;
import com.smartstaff.entity.Difficulty;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;
import com.smartstaff.entity.QuestionType;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.smartstaff.entity.Difficulty.*;
import static com.smartstaff.entity.QuestionType.*;

/** Default blueprints per level and role profile (architecture doc, "Question
 *  model"). Every blueprint is 100 points.
 *
 *  Technical roles
 *    L1 (~45 min, theory 30): 6 concepts (4 MCQ + 2 MSQ) x5, CODE_WRITE easy 15 +
 *       medium 20, CODE_DEBUG 15, 2 CODE_OUTPUT x5, SCENARIO 10 (LOGIC for juniors)
 *    L2 (theory 20): 4 concepts x5, CODE_WRITE medium 18 + hard 22, CODE_DEBUG 15,
 *       CODE_OUTPUT 10, design SCENARIO 15
 *    L3 (theory 15): 3 concepts x5, 2 CODE_WRITE hard x20, CODE_DEBUG 15,
 *       CODE_OUTPUT 10, design SCENARIO 20
 *  Non-technical roles (theory + scenario only)
 *    L1: 8 concepts x5 + 3 SCENARIO x20;  L2: 6 concepts x5 + SCENARIO 20/25/25;
 *    L3: 4 concepts x5 + 4 SCENARIO x20
 *
 *  Each slot's target skill is picked from the profile's skill weights with a
 *  smooth weighted round-robin, so heavier skills get proportionally more
 *  slots and the result is deterministic. Coding slots only target code
 *  skills (languages and code frameworks). */
@Component
public class BlueprintFactory {

    public static final List<String> LEVELS = List.of("L1", "L2", "L3");
    /** Languages the code runner offers at launch, in preference order. */
    public static final List<String> RUNNER_LANGUAGES = List.of("java", "python", "javascript", "cpp");

    private record Spec(QuestionType type, int points, Difficulty difficulty, boolean design) {
        static Spec of(QuestionType type, int points, Difficulty difficulty) {
            return new Spec(type, points, difficulty, false);
        }
    }

    public Map<String, Blueprint> createAll(JobRoleProfile profile) {
        Map<String, Blueprint> all = new LinkedHashMap<>();
        for (String level : LEVELS) all.put(level, create(level, profile));
        return all;
    }

    public Blueprint create(String level, JobRoleProfile profile) {
        boolean technical = Boolean.TRUE.equals(profile.getIsTechnical())
                && profile.getRoleFamily() != RoleFamily.NON_TECHNICAL;
        List<Spec> specs = technical ? technicalSpecs(level, profile.getSeniority()) : nonTechnicalSpecs(level);

        List<String> ranked = rankedSkills(profile.getSkillWeights());
        Set<String> codeCapable = codeSkills(profile, ranked);
        List<String> codePool = ranked.stream().filter(codeCapable::contains).toList();
        WeightedPicker anyPicker = new WeightedPicker(ranked, profile.getSkillWeights());
        WeightedPicker codePicker = new WeightedPicker(codePool.isEmpty() ? ranked : codePool, profile.getSkillWeights());

        List<Slot> slots = new ArrayList<>();
        for (Spec spec : specs) {
            String skill = (spec.type().isCode() ? codePicker : anyPicker).next();
            slots.add(new Slot(slots.size(), spec.type(), spec.type().competency(), spec.type().dimension(),
                    spec.points(), spec.difficulty(), skill, timeEstimate(spec), spec.design()));
        }

        int totalSeconds = slots.stream().mapToInt(Slot::timeEstimateSec).sum();
        return new Blueprint(level, profile.getRoleFamily(), technical,
                technical ? codingLanguages(profile) : List.of(),
                slots.stream().mapToInt(Slot::points).sum(),
                (totalSeconds + 59) / 60,
                List.copyOf(slots));
    }

    // ── templates ───────────────────────────────────────────────────────

    private static List<Spec> technicalSpecs(String level, SeniorityLevel seniority) {
        List<Spec> s = new ArrayList<>();
        switch (level) {
            case "L1" -> {
                concepts(s, 4, 2, EASY);
                s.add(Spec.of(CODE_WRITE, 15, EASY));
                s.add(Spec.of(CODE_WRITE, 20, MEDIUM));
                s.add(Spec.of(CODE_DEBUG, 15, EASY));
                s.add(Spec.of(CODE_OUTPUT, 5, EASY));
                s.add(Spec.of(CODE_OUTPUT, 5, EASY));
                s.add(Spec.of(seniority == SeniorityLevel.JUNIOR ? LOGIC : SCENARIO, 10, MEDIUM));
            }
            case "L2" -> {
                concepts(s, 3, 1, MEDIUM);
                s.add(Spec.of(CODE_WRITE, 18, MEDIUM));
                s.add(Spec.of(CODE_WRITE, 22, HARD));
                s.add(Spec.of(CODE_DEBUG, 15, MEDIUM));
                s.add(Spec.of(CODE_OUTPUT, 10, MEDIUM));
                s.add(new Spec(SCENARIO, 15, MEDIUM, true));
            }
            case "L3" -> {
                concepts(s, 2, 1, HARD);
                s.add(Spec.of(CODE_WRITE, 20, HARD));
                s.add(Spec.of(CODE_WRITE, 20, HARD));
                s.add(Spec.of(CODE_DEBUG, 15, HARD));
                s.add(Spec.of(CODE_OUTPUT, 10, HARD));
                s.add(new Spec(SCENARIO, 20, HARD, true));
            }
            default -> throw new IllegalArgumentException("Unknown level " + level);
        }
        return s;
    }

    private static List<Spec> nonTechnicalSpecs(String level) {
        List<Spec> s = new ArrayList<>();
        switch (level) {
            case "L1" -> {
                concepts(s, 6, 2, EASY);
                for (int i = 0; i < 3; i++) s.add(Spec.of(SCENARIO, 20, EASY));
            }
            case "L2" -> {
                concepts(s, 4, 2, MEDIUM);
                s.add(Spec.of(SCENARIO, 20, MEDIUM));
                s.add(Spec.of(SCENARIO, 25, MEDIUM));
                s.add(Spec.of(SCENARIO, 25, MEDIUM));
            }
            case "L3" -> {
                concepts(s, 3, 1, HARD);
                for (int i = 0; i < 4; i++) s.add(Spec.of(SCENARIO, 20, HARD));
            }
            default -> throw new IllegalArgumentException("Unknown level " + level);
        }
        return s;
    }

    private static void concepts(List<Spec> s, int mcq, int msq, Difficulty difficulty) {
        for (int i = 0; i < mcq; i++) s.add(Spec.of(MCQ, 5, difficulty));
        for (int i = 0; i < msq; i++) s.add(Spec.of(MSQ, 5, difficulty));
    }

    private static int timeEstimate(Spec spec) {
        return switch (spec.type()) {
            case MCQ -> 60;
            case MSQ -> 90;
            case CODE_WRITE -> byDifficulty(spec.difficulty(), 540, 720, 900);
            case CODE_DEBUG -> byDifficulty(spec.difficulty(), 420, 540, 660);
            case CODE_OUTPUT -> byDifficulty(spec.difficulty(), 150, 210, 270);
            case SCENARIO -> spec.design() ? 600 : 300;
            case LOGIC -> 240;
        };
    }

    private static int byDifficulty(Difficulty d, int easy, int medium, int hard) {
        return switch (d) {
            case EASY -> easy;
            case MEDIUM -> medium;
            case HARD -> hard;
        };
    }

    // ── skills and languages ────────────────────────────────────────────

    private static List<String> rankedSkills(Map<String, Integer> weights) {
        if (weights == null) return List.of();
        return weights.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static Set<String> codeSkills(JobRoleProfile profile, List<String> skills) {
        Set<String> declared = new HashSet<>();
        if (profile.getLanguages() != null) profile.getLanguages().forEach(l -> declared.add(l.toLowerCase(Locale.ROOT)));
        if (profile.getFrameworks() != null) profile.getFrameworks().forEach(f -> declared.add(f.toLowerCase(Locale.ROOT)));
        Set<String> out = new HashSet<>();
        for (String s : skills) {
            if (declared.contains(s) || RoleProfileRules.isCodeSkill(s)) out.add(s);
        }
        return out;
    }

    /** Profile languages the runner supports, else Python as a neutral default. */
    static List<String> codingLanguages(JobRoleProfile profile) {
        List<String> declared = profile.getLanguages() == null ? List.of() : profile.getLanguages();
        List<String> supported = declared.stream()
                .map(l -> "typescript".equals(l) ? "javascript" : l)
                .filter(RUNNER_LANGUAGES::contains)
                .distinct()
                .toList();
        return supported.isEmpty() ? List.of("python") : supported;
    }

    /** Smooth weighted round-robin (as in nginx): each pick adds every skill's
     *  weight to its running score, takes the highest, and subtracts the total
     *  from the winner. Ties go to the higher-ranked skill. */
    static final class WeightedPicker {
        private final List<String> skills;
        private final int[] weights;
        private final int[] current;
        private final int total;

        WeightedPicker(List<String> skills, Map<String, Integer> weightMap) {
            this.skills = skills;
            this.weights = skills.stream().mapToInt(s -> Math.max(1, weightMap.getOrDefault(s, 1))).toArray();
            this.current = new int[skills.size()];
            this.total = Arrays.stream(weights).sum();
        }

        String next() {
            if (skills.isEmpty()) return null;
            int best = 0;
            for (int i = 0; i < skills.size(); i++) {
                current[i] += weights[i];
                if (current[i] > current[best]) best = i;
            }
            current[best] -= total;
            return skills.get(best);
        }
    }
}
