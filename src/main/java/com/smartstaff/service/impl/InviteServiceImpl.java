package com.smartstaff.service.impl;

import com.smartstaff.dto.request.InviteMintRequest;
import com.smartstaff.dto.response.InviteMintResponse;
import com.smartstaff.dto.response.InviteResponse;
import com.smartstaff.entity.Invite;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.InviteRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.InviteService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Mints single-use, expiring candidate assessment links. Only the SHA-256
 *  hash of each raw token is persisted (see {@link Invite} /
 *  V6__assessments.sql), per SMARTSTAFF_BACKEND_DESIGN.md's "store invite
 *  token hashes, not raw tokens" requirement.
 *
 *  Deliberate deviation: Candidates.jsx's comments describe the original
 *  app "coalescing" tokens (same email+level returning an identical token
 *  across repeated mint calls). That's impossible here without keeping the
 *  raw token somewhere retrievable, which would defeat the hash-only
 *  storage the design doc asks for — so every call mints a fresh token
 *  instead. The frontend tolerates this fine: it just uses whatever the
 *  latest mint response returns, and older unused invites simply expire
 *  untouched. See docs/FEATURES.md. */
@Service
public class InviteServiceImpl implements InviteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JobRepository jobRepository;
    private final InviteRepository inviteRepository;
    private final SettingsService settingsService;

    public InviteServiceImpl(JobRepository jobRepository, InviteRepository inviteRepository, SettingsService settingsService) {
        this.jobRepository = jobRepository;
        this.inviteRepository = inviteRepository;
        this.settingsService = settingsService;
    }

    @Override
    @Transactional
    public InviteMintResponse mint(InviteMintRequest req, User admin) {
        UUID jobId = parseSessionId(req.session_id());
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        List<String> levels = req.levels();
        if (levels == null || levels.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one level is required.");
        }
        if (req.candidate_email() == null || req.candidate_email().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "candidate_email is required.");
        }

        long ttlSeconds = settingsService.getInviteTtlSeconds();
        String createdBy = admin == null ? null : admin.publicId();
        List<InviteResponse> out = new ArrayList<>();

        if (req.combined()) {
            out.add(mintOne(job, req, levels, true, ttlSeconds, createdBy));
        } else {
            for (String level : levels) {
                out.add(mintOne(job, req, List.of(level), false, ttlSeconds, createdBy));
            }
        }

        return new InviteMintResponse(true, out);
    }

    private InviteResponse mintOne(Job job, InviteMintRequest req, List<String> levels, boolean combined,
                                    long ttlSeconds, String createdBy) {
        String rawToken = randomToken();

        Invite invite = new Invite();
        invite.setTokenHash(FileStorageService.sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8)));
        invite.setKind("ASSESSMENT");
        invite.setJob(job);
        invite.setCandidateEmail(req.candidate_email().trim());
        invite.setCandidateName(req.candidate_name());
        invite.setLevels(levels);
        invite.setCombined(combined);
        invite.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        invite.setCreatedBy(createdBy);
        inviteRepository.save(invite);

        String levelParam = String.join(",", levels);
        String paramName = combined ? "levels" : "level";
        String url = settingsService.getPublicBaseUrl() + "/assessment/" + job.jdNumberDisplay()
                + "?" + paramName + "=" + levelParam + "&token=" + rawToken;

        return new InviteResponse(levelParam, url, ttlSeconds);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No job description found for this session — upload a JD first.");
        }
    }
}
