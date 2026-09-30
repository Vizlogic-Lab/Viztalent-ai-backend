package com.smartstaff.service;

import java.util.UUID;

/** An attempt was submitted (or auto-submitted); scoring starts after commit. */
public record AttemptSubmittedEvent(UUID attemptId) {}
