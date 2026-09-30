package com.smartstaff.service;

import java.util.UUID;

/** Published inside the JD upload transaction; role-profile extraction
 *  listens for it after commit. */
public record JdUploadedEvent(UUID jobId) {}
