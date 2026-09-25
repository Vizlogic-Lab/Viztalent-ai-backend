package com.smartstaff.repository;

import com.smartstaff.entity.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByTokenHash(String tokenHash);

    /** Candidates for coalescing — caller filters by exact `levels` match
     *  (jsonb equality isn't worth a bespoke query for this small a table). */
    List<Invite> findByJobIdAndCandidateEmailIgnoreCaseAndCombinedAndUsedAtIsNullAndExpiresAtAfter(
            UUID jobId, String candidateEmail, boolean combined, Instant now);

    /** Atomic single-use redemption per SMARTSTAFF_BACKEND_DESIGN.md ("consume with
     *  an atomic UPDATE ... WHERE used_at IS NULL AND expires_at > now()") — returns
     *  1 if this call is the one that consumed it, 0 if it was already used/expired
     *  (including by a concurrent request that won the race). */
    @Modifying
    @Transactional
    @Query("update Invite i set i.usedAt = :now where i.id = :id and i.usedAt is null and i.expiresAt > :now")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
