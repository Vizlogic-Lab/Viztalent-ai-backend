package com.smartstaff.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-that-is-comfortably-long-enough";

    private final JwtService jwt = new JwtService(SECRET, 60);

    @Test
    @DisplayName("a token resolves back to the user it was issued for")
    void roundTrip() {
        UUID userId = UUID.randomUUID();

        assertThat(jwt.validateAndGetUserId(jwt.issueToken(userId, "ADMIN"))).contains(userId);
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void wrongSecretRejected() {
        String foreign = new JwtService("another-secret-that-is-also-long-enough-0000", 60).issueToken(UUID.randomUUID(), "ADMIN");

        assertThat(jwt.validateAndGetUserId(foreign)).isEmpty();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredRejected() {
        String expired = new JwtService(SECRET, -1).issueToken(UUID.randomUUID(), "USER");

        assertThat(jwt.validateAndGetUserId(expired)).isEmpty();
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedRejected() {
        String token = jwt.issueToken(UUID.randomUUID(), "USER");
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

        assertThat(jwt.validateAndGetUserId(tampered)).isEmpty();
    }

    @Test
    @DisplayName("garbage input is rejected without throwing")
    void garbageRejected() {
        assertThat(jwt.validateAndGetUserId("not.a.jwt")).isEmpty();
        assertThat(jwt.validateAndGetUserId("")).isEmpty();
    }

    @Test
    @DisplayName("a too-short secret is padded rather than crashing startup (dev convenience)")
    void shortSecretPadded() {
        assertThatCode(() -> new JwtService("short", 60)).doesNotThrowAnyException();
    }
}
