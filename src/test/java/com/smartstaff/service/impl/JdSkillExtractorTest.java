package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdSkillExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesAndCleansTheModelOutput() throws Exception {
        var r = JdSkillExtractor.parse(json.readTree("""
                {"must_have":[
                   {"skill":" Project Coordination ","aliases":["coordinator","project coordinator","PMO","pm"]},
                   {"skill":"stakeholder management","aliases":["stakeholders","stakeholders"]},
                   {"skill":"project coordination","aliases":[]}],
                 "nice_to_have":[
                   {"skill":"SAP","aliases":["sap s/4hana"]},
                   {"skill":"stakeholder management","aliases":[]}],
                 "experience_min_years":3,"experience_max_years":0}
                """));
        assertThat(r.mustHave()).containsExactly("project coordination", "stakeholder management");
        assertThat(r.niceToHave()).containsExactly("sap");            // a must-have is not repeated as nice
        assertThat(r.aliases().get("project coordination"))
                .containsExactly("coordinator", "project coordinator", "pmo"); // "pm" dropped: too short/generic
        assertThat(r.aliases().get("stakeholder management")).containsExactly("stakeholders");
        assertThat(r.minYears()).isEqualTo(3);
        assertThat(r.maxYears()).isNull();
    }

    @Test
    void minAboveMaxDropsTheMax() throws Exception {
        var r = JdSkillExtractor.parse(json.readTree("""
                {"must_have":[{"skill":"jira","aliases":[]}],"nice_to_have":[],
                 "experience_min_years":8,"experience_max_years":5}"""));
        assertThat(r.minYears()).isEqualTo(8);
        assertThat(r.maxYears()).isNull();
    }

    @Test
    void noSkillsIsAnError() {
        assertThatThrownBy(() -> JdSkillExtractor.parse(json.readTree(
                "{\"must_have\":[],\"nice_to_have\":[],\"experience_min_years\":0,\"experience_max_years\":0}")))
                .isInstanceOf(IllegalStateException.class);
    }
}
