package com.smartstaff.service;

import com.smartstaff.dto.request.RoleProfileRequest;
import com.smartstaff.dto.response.RoleProfileResponse;
import com.smartstaff.entity.JobRoleProfile;

import java.util.UUID;

public interface RoleProfileService {

    /**
     * Extract role profile for a job using Gemini, with fallback to rule-based extraction.
     * Does NOT require the caller to be inside a transaction (runs async).
     */
    void extractRoleProfile(UUID jobId);

    /**
     * Get the role profile for a job.
     * @return RoleProfileResponse or throws ApiException 404 if not found
     */
    RoleProfileResponse getRoleProfile(UUID jobId);

    /**
     * HR edits a job's role profile. Sets source=HR and edited_by.
     * Requires admin access (checked by controller).
     */
    void updateRoleProfile(UUID jobId, RoleProfileRequest request, UUID editorId);

    /**
     * Get the raw entity (for internal use).
     */
    JobRoleProfile getRoleProfileEntity(UUID jobId);
}
