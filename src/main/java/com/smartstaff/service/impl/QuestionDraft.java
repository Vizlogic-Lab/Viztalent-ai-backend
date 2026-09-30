package com.smartstaff.service.impl;

import com.smartstaff.entity.AssessmentQuestion;

import java.util.Map;

/** A candidate question for one blueprint slot, before validation. The naive
 *  solution is only used to prove the hidden tests discriminate and is never
 *  stored. */
public record QuestionDraft(AssessmentQuestion question, Map<String, String> naiveSolution) {}
