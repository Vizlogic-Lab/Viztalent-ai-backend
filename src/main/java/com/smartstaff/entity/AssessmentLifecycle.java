package com.smartstaff.entity;

/** Authoring state of an assessment version, separate from generation {@link AssessmentStatus}.
 *  DRAFT -> PUBLISHED -> ARCHIVED. Only a PUBLISHED version can be the job's current one. */
public enum AssessmentLifecycle { DRAFT, PUBLISHED, ARCHIVED }
