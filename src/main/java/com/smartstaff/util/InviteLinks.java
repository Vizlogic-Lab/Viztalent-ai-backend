package com.smartstaff.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Candidate invite tokens and the links that carry them. Only a token's
 *  SHA-256 hash is ever stored; the raw token exists in the link alone. */
public final class InviteLinks {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteLinks() {}

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawToken) {
        return FileStorageService.sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
    }

    public static String assessment(String publicBaseUrl, String jdNumber, List<String> levels, boolean combined, String rawToken) {
        return publicBaseUrl + "/assessment/" + jdNumber + "?" + (combined ? "levels" : "level") + "="
                + String.join(",", levels) + "&token=" + rawToken;
    }

    public static String interview(String publicBaseUrl, String rawToken) {
        return publicBaseUrl + "/interview/" + rawToken;
    }
}
