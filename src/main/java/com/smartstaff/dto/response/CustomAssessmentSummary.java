package com.smartstaff.dto.response;

import java.time.Instant;
import java.util.UUID;

/** One row of a job's hand-authored assessments. */
public record CustomAssessmentSummary(UUID id, String title, String lifecycle, int version, boolean current,
                                      int questions, int total_marks, boolean locked, Instant updated_at) {}
