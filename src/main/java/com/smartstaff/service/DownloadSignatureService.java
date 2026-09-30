package com.smartstaff.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * Generates and validates signed download URLs.
 * Signatures: HMAC-SHA256(path|exp, JWT_SECRET), valid for 5 minutes.
 */
@Service
public class DownloadSignatureService {

    private static final long SIGNATURE_TTL_SECONDS = 300; // 5 minutes
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String jwtSecret;

    public DownloadSignatureService(@Value("${app.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /**
     * Generate a signed URL for a download path.
     *
     * @param path The download path (e.g., "/api/jd/123/download")
     * @return A URL with ?exp=<epochSeconds>&sig=<signature>
     */
    public String generateSignedUrl(String path) {
        long expiresAt = Instant.now().getEpochSecond() + SIGNATURE_TTL_SECONDS;
        String signature = computeSignature(path, expiresAt);
        return path + "?exp=" + expiresAt + "&sig=" + signature;
    }

    /**
     * Validate a signature. Returns true only if the signature matches and is not expired.
     *
     * @param path The path that was signed
     * @param expiresAtStr The expiration time (seconds since epoch)
     * @param signatureStr The signature to validate
     * @return true if valid and not expired, false otherwise
     */
    public boolean isSignatureValid(String path, String expiresAtStr, String signatureStr) {
        try {
            long expiresAt = Long.parseLong(expiresAtStr);

            // Check if expired
            if (expiresAt < Instant.now().getEpochSecond()) {
                return false;
            }

            // Check if signature matches
            String expectedSig = computeSignature(path, expiresAt);
            return constantTimeEquals(expectedSig, signatureStr);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String computeSignature(String path, long expiresAt) {
        try {
            String data = path + "|" + expiresAt;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Signature generation failed", e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);

        if (aBytes.length != bBytes.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
