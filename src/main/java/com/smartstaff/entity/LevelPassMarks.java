package com.smartstaff.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Pass marks for one assessment level. A null field means "not set here": it inherits
 *  from the next level up (job override, then global, then the built-in defaults).
 *  overall_pass: the overall score (0-100) a candidate must reach; 0 means no overall gate.
 *  proceed_hands_on_min / proceed_theory_min: the two PROCEED gates. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LevelPassMarks(Integer overall_pass, Integer proceed_hands_on_min, Integer proceed_theory_min) {

    /** Null when every set field is 0-100, else what's wrong. */
    public String problem() {
        for (Integer v : new Integer[] {overall_pass, proceed_hands_on_min, proceed_theory_min}) {
            if (v != null && (v < 0 || v > 100)) return "Pass marks must be between 0 and 100.";
        }
        return null;
    }
}
