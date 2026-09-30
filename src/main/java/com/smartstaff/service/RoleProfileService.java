package com.smartstaff.service;

import com.smartstaff.dto.request.RoleProfileRequest;
import com.smartstaff.dto.response.RoleProfileResponse;
import com.smartstaff.entity.User;

import java.util.Optional;
import java.util.UUID;

public interface RoleProfileService {

    /** Builds the job's profile with one Gemini call, falling back to rules
     *  when Gemini is unconfigured or fails. Runs on the profile executor and
     *  never inside a caller's transaction. An HR-edited profile is kept. */
    void extractRoleProfile(UUID jobId);

    RoleProfileResponse getRoleProfile(UUID jobId);

    Optional<RoleProfileResponse> findRoleProfile(UUID jobId);

    /** HR correction: sets source=HR and edited_by. */
    RoleProfileResponse updateRoleProfile(UUID jobId, RoleProfileRequest request, User editor);
}
