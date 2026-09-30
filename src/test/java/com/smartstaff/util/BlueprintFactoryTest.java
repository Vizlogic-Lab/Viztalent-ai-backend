package com.smartstaff.util;

import com.smartstaff.entity.Blueprint;
import com.smartstaff.entity.Blueprint.Slot;
import com.smartstaff.entity.Dimension;
import com.smartstaff.entity.JobRoleProfile;
import com.smartstaff.entity.JobRoleProfile.RoleFamily;
import com.smartstaff.entity.JobRoleProfile.SeniorityLevel;
import com.smartstaff.entity.QuestionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Collectors;

import static com.smartstaff.entity.QuestionType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueprintFactoryTest {

    private final BlueprintFactory factory = new BlueprintFactory();

    private static JobRoleProfile profile(RoleFamily family, boolean technical, SeniorityLevel seniority,
                                          List<String> languages, Map<String, Integer> weights) {
        JobRoleProfile p = new JobRoleProfile();
        p.setJobId(UUID.randomUUID());
        p.setRoleFamily(family);
        p.setIsTechnical(technical);
        p.setSeniority(seniority);
        p.setLanguages(languages);
        p.setFrameworks(List.of());
        p.setSkillWeights(new LinkedHashMap<>(weights));
        return p;
    }

    private static JobRoleProfile javaBackend() {
        return profile(RoleFamily.BACKEND, true, SeniorityLevel.MID, List.of("java"),
                Map.of("java", 5, "spring", 4, "sql", 3, "docker", 2));
    }

    private static Map<QuestionType, Long> counts(Blueprint b) {
        return b.slots().stream().collect(Collectors.groupingBy(Slot::type, TreeMap::new, Collectors.counting()));
    }

    private static int points(Blueprint b, QuestionType type) {
        return b.slots().stream().filter(s -> s.type() == type).mapToInt(Slot::points).sum();
    }

    @Test
    void technicalL1MatchesTheArchitectureDoc() {
        Blueprint b = factory.create("L1", javaBackend());

        assertThat(counts(b)).containsExactlyInAnyOrderEntriesOf(Map.of(
                MCQ, 4L, MSQ, 2L, CODE_WRITE, 2L, CODE_DEBUG, 1L, CODE_OUTPUT, 2L, SCENARIO, 1L));
        assertThat(points(b, MCQ) + points(b, MSQ)).isEqualTo(30);
        assertThat(points(b, CODE_WRITE)).isEqualTo(35);
        assertThat(points(b, CODE_DEBUG)).isEqualTo(15);
        assertThat(points(b, CODE_OUTPUT)).isEqualTo(10);
        assertThat(points(b, SCENARIO)).isEqualTo(10);
        assertThat(b.points(Dimension.THEORY)).isEqualTo(30);
        assertThat(b.points(Dimension.HANDS_ON)).isEqualTo(70);
        assertThat(b.totalPoints()).isEqualTo(100);
        assertThat(b.durationMinutes()).isEqualTo(45);
        assertThat(b.slots().stream().filter(s -> s.type() == CODE_WRITE).map(s -> s.difficulty().label()))
                .containsExactly("easy", "medium");
    }

    @Test
    void juniorL1GetsLogicInsteadOfScenario() {
        JobRoleProfile p = javaBackend();
        p.setSeniority(SeniorityLevel.JUNIOR);
        Map<QuestionType, Long> c = counts(factory.create("L1", p));
        assertThat(c).containsEntry(LOGIC, 1L).doesNotContainKey(SCENARIO);
    }

    @Test
    void technicalL2AndL3HaveSmallerTheoryAndADesignScenario() {
        Blueprint l2 = factory.create("L2", javaBackend());
        Blueprint l3 = factory.create("L3", javaBackend());

        assertThat(l2.points(Dimension.THEORY)).isEqualTo(20);
        assertThat(l3.points(Dimension.THEORY)).isEqualTo(15);
        for (Blueprint b : List.of(l2, l3)) {
            assertThat(b.totalPoints()).isEqualTo(100);
            assertThat(counts(b)).containsEntry(CODE_WRITE, 2L).containsEntry(CODE_DEBUG, 1L)
                    .containsEntry(CODE_OUTPUT, 1L).containsEntry(SCENARIO, 1L);
            assertThat(b.slots().stream().filter(s -> s.type() == SCENARIO)).allMatch(Slot::designScenario);
        }
        assertThat(l2.slots().stream().filter(s -> s.type() == CODE_WRITE).map(s -> s.difficulty().label()))
                .containsExactly("medium", "hard");
        assertThat(l3.slots().stream().filter(s -> s.type().isCode()).map(s -> s.difficulty().label()))
                .containsOnly("hard");
    }

    @ParameterizedTest
    @EnumSource(value = RoleFamily.class, names = "NON_TECHNICAL", mode = EnumSource.Mode.EXCLUDE)
    void everyTechnicalFamilyGetsCodingAtEveryLevel(RoleFamily family) {
        JobRoleProfile p = profile(family, true, SeniorityLevel.MID, List.of("python"), Map.of("python", 5, "sql", 3));
        for (String level : BlueprintFactory.LEVELS) {
            Blueprint b = factory.create(level, p);
            assertThat(b.technical()).isTrue();
            assertThat(b.roleFamily()).isEqualTo(family);
            assertThat(b.totalPoints()).isEqualTo(100);
            assertThat(counts(b)).containsKeys(CODE_WRITE, CODE_DEBUG, CODE_OUTPUT);
            assertThat(b.languages()).containsExactly("python");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1", "L2", "L3"})
    void nonTechnicalIsTheoryPlusScenarioOnly(String level) {
        JobRoleProfile p = profile(RoleFamily.NON_TECHNICAL, false, SeniorityLevel.MID, List.of(),
                Map.of("dms", 5, "sfa", 5, "excel", 3));
        Blueprint b = factory.create(level, p);

        assertThat(b.technical()).isFalse();
        assertThat(b.languages()).isEmpty();
        assertThat(b.totalPoints()).isEqualTo(100);
        assertThat(counts(b).keySet()).containsOnly(MCQ, MSQ, SCENARIO);
        assertThat(b.slots()).extracting(Slot::targetSkill).containsAnyOf("dms", "sfa").doesNotContainNull();
        int theory = b.points(Dimension.THEORY);
        assertThat(theory).isEqualTo(switch (level) { case "L1" -> 40; case "L2" -> 30; default -> 20; });
    }

    @Test
    void technicalFlagOffMeansNonTechnicalBlueprint() {
        JobRoleProfile p = profile(RoleFamily.DATA, false, SeniorityLevel.MID, List.of(), Map.of("tableau", 5));
        assertThat(counts(factory.create("L1", p)).keySet()).containsOnly(MCQ, MSQ, SCENARIO);
    }

    @Test
    void targetSkillsFollowWeightsAndCodingSlotsOnlyTargetCodeSkills() {
        Blueprint b = factory.create("L1", javaBackend());

        List<String> codeTargets = b.slots().stream().filter(s -> s.type().isCode()).map(Slot::targetSkill).toList();
        assertThat(codeTargets).doesNotContain("docker").contains("java", "spring", "sql");
        assertThat(codeTargets.get(0)).as("heaviest skill first").isEqualTo("java");

        Map<String, Long> all = b.slots().stream().collect(Collectors.groupingBy(Slot::targetSkill, Collectors.counting()));
        assertThat(all.get("java")).isGreaterThanOrEqualTo(all.get("sql"));
        assertThat(all.get("sql")).isGreaterThanOrEqualTo(all.getOrDefault("docker", 0L));
    }

    @Test
    void deterministic() {
        assertThat(factory.createAll(javaBackend())).isEqualTo(factory.createAll(javaBackend()));
    }

    @Test
    void codingLanguagesDefaultToPythonAndMapTypescript() {
        JobRoleProfile devops = profile(RoleFamily.DEVOPS, true, SeniorityLevel.MID, List.of("go"),
                Map.of("docker", 5, "kubernetes", 4));
        Blueprint b = factory.create("L1", devops);
        assertThat(b.languages()).containsExactly("python");
        assertThat(b.slots().stream().filter(s -> s.type().isCode()).map(Slot::targetSkill))
                .as("no code skill in the profile, so code slots fall back to the top skills")
                .containsOnly("docker", "kubernetes");

        JobRoleProfile frontend = profile(RoleFamily.FRONTEND, true, SeniorityLevel.MID, List.of("typescript"),
                Map.of("react", 5));
        assertThat(factory.create("L1", frontend).languages()).containsExactly("javascript");
    }

    @Test
    void emptySkillWeightsLeaveTargetsEmpty() {
        JobRoleProfile p = profile(RoleFamily.BACKEND, true, SeniorityLevel.MID, List.of("java"), Map.of());
        assertThat(factory.create("L1", p).slots()).extracting(Slot::targetSkill).containsOnlyNulls();
    }

    @Test
    void slotsAreNumberedInOrder() {
        List<Slot> slots = factory.create("L2", javaBackend()).slots();
        for (int i = 0; i < slots.size(); i++) assertThat(slots.get(i).seq()).isEqualTo(i);
        assertThat(slots.get(0).type()).isIn(MCQ, MSQ);
    }

    @Test
    void unknownLevelRejected() {
        assertThatThrownBy(() -> factory.create("L9", javaBackend())).isInstanceOf(IllegalArgumentException.class);
    }
}
