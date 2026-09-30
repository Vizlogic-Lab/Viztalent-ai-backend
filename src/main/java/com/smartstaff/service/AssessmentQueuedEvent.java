package com.smartstaff.service;

import java.util.UUID;

/** A new assessment version was queued; generation starts after commit. */
public record AssessmentQueuedEvent(UUID assessmentId) {}
