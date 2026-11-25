package com.smartstaff.service;

import java.util.UUID;

/** Published after screening commits: score the job's PENDING candidates with the AI matcher. */
public record AiMatchRequestedEvent(UUID jobId) {}
