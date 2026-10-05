package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The checks on Gemini's personal-assessment reply, and the prompt rules. */
class PersonalAssessmentRulesTest {

    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode reply(int mcqs, int experience, int bugs, int devops, int agile) {
        ObjectNode root = om.createObjectNode();
        ArrayNode m = root.putArray("mcqs");
        for (int i = 0; i < mcqs; i++) {
            ObjectNode q = m.addObject();
            q.put("question", "MCQ " + i + "?");
            q.putArray("options").add("one").add("two").add("three").add("four");
            q.put("correct_option", "B");
            q.put("explanation", "because");
            q.put("skill", "java");
        }
        open(root.putArray("experience"), "exp", experience, false);
        open(root.putArray("bug_fixing"), "bug", bugs, true);
        open(root.putArray("devops"), "ops", devops, false);
        open(root.putArray("agile"), "agile", agile, false);
        return root;
    }

    private static void open(ArrayNode arr, String prefix, int n, boolean snippet) {
        for (int i = 0; i < n; i++) {
            ObjectNode q = arr.addObject();
            q.put("question", prefix + " question " + i + "?");
            if (snippet) q.put("code_snippet", "int x = 1 / 0;");
            q.put("model_answer", "answer");
            q.putArray("key_points").add("point one").add("point two").add("point three");
            q.put("skill", "spring");
        }
    }

    @Test
    void acceptsTheFiveSectionShape() {
        var p = PersonalAssessmentGenerator.parse(reply(5, 4, 3, 2, 1));
        assertThat(p.mcqs()).hasSize(5);
        assertThat(p.mcqs().get(0).correct()).isEqualTo(1);
        assertThat(p.open()).hasSize(4 + 3 + 2 + 1);
        assertThat(p.open().stream().filter(o -> o.section().equals("bug_fixing")).allMatch(o -> o.snippet() != null)).isTrue();
    }

    @Test
    void trimsSurplusAndRejectsShortfall() {
        var p = PersonalAssessmentGenerator.parse(reply(7, 4, 2, 1, 1));
        assertThat(p.mcqs()).hasSize(5);
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(reply(5, 3, 2, 1, 1)))
                .hasMessageContaining("experience");
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(reply(5, 4, 1, 1, 1)))
                .hasMessageContaining("bug_fixing");
    }

    @Test
    void rejectsBadMcqsAndRepeats() {
        ObjectNode r = reply(5, 4, 2, 1, 1);
        ((ObjectNode) r.get("mcqs").get(0)).put("correct_option", "E");
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(r)).hasMessageContaining("correct_option");

        ObjectNode dup = reply(5, 4, 2, 1, 1);
        ((ObjectNode) dup.get("mcqs").get(1)).put("question", "MCQ 0?");
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(dup)).hasMessageContaining("repeated");

        ObjectNode sameOpts = reply(5, 4, 2, 1, 1);
        ((ArrayNode) sameOpts.get("mcqs").get(0).get("options")).set(1, om.getNodeFactory().textNode("ONE"));
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(sameOpts)).hasMessageContaining("four distinct");
    }

    @Test
    void bugFixingNeedsASnippet() {
        ObjectNode r = reply(5, 4, 2, 1, 1);
        ((ObjectNode) r.get("bug_fixing").get(0)).remove("code_snippet");
        assertThatThrownBy(() -> PersonalAssessmentGenerator.parse(r)).hasMessageContaining("code_snippet");
    }

    @Test
    void promptMustKeepBothPlaceholders() {
        assertThat(AssessmentPromptService.validate("x {{INSERT_JOB_DESCRIPTION}} {{INSERT_CANDIDATE_RESUME}}")).isNull();
        assertThat(AssessmentPromptService.validate("x {{INSERT_JOB_DESCRIPTION}}")).contains("RESUME");
        assertThat(AssessmentPromptService.validate("")).isNotNull();
        assertThat(AssessmentPromptService.render("JD:{{INSERT_JOB_DESCRIPTION}} CV:{{INSERT_CANDIDATE_RESUME}}", "j", "r"))
                .isEqualTo("JD:j CV:r");
    }

    @Test
    void schemaListsEverySection() {
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) PersonalAssessmentGenerator.schema().get("properties");
        assertThat(props).containsKeys("mcqs", "experience", "bug_fixing", "devops", "agile");
    }
}
