package com.smartstaff.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.ttl-minutes}") long ttlMinutes
    ) {
        // HS256 needs >= 256 bits; pad a short dev secret rather than fail startup.
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMinutes = ttlMinutes;
    }

    /** Claim holding the account's token version (see V30__token_version.sql). */
    static final String TOKEN_VERSION_CLAIM = "tv";

    /** A token whose signature and expiry checked out. */
    public record VerifiedToken(UUID userId, int tokenVersion) {}

    public String issueToken(UUID userId, String role) {
        return issueToken(userId, role, 0);
    }

    public String issueToken(UUID userId, String role, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Optional<UUID> validateAndGetUserId(String token) {
        return verify(token).map(VerifiedToken::userId);
    }

    /** Signature + expiry check; a token without a version claim counts as version 0. */
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Integer version = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
            return Optional.of(new VerifiedToken(UUID.fromString(claims.getSubject()), version == null ? 0 : version));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
