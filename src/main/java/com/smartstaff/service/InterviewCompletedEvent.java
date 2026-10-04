package com.smartstaff.service;

import java.util.UUID;

/** F16: an interview became COMPLETED (browser, self-service or phone); grading starts after commit. */
public record InterviewCompletedEvent(UUID interviewId) {}
