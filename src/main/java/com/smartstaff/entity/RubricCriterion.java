package com.smartstaff.entity;

/** One weighted criterion of a question's grading rubric, stored as jsonb. */
public record RubricCriterion(String criterion, int weight, String description) {}
