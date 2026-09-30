package com.smartstaff.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Short-lived signed download URLs: {@code path?exp=<epochSeconds>&uid=<userId>&sig=<HMAC>}.
 *  The signature covers path, expiry and the signing user, so a link can be
 *  opened by a plain browser navigation (no bearer header) while the endpoint
 *  still runs its normal per-user authorization as the user who signed it. */
@Service
public class DownloadSignatureService {

    public static final long TTL_SECONDS = 300;
    private static final String HMAC = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public DownloadSignatureService(@Value("${app.jwt.secret}") String jwtSecret) {
        this(jwtSecret, Clock.systemUTC());
    }

    DownloadSignatureService(String jwtSecret, Clock clock) {
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String path, UUID userId) {
        long exp = clock.instant().getEpochSecond() + TTL_SECONDS;
        return path + "?exp=" + exp + "&uid=" + userId + "&sig=" + signature(path, exp, userId);
    }

    /** The signing user's id if the signature is intact and unexpired. */
    public Optional<UUID> verify(String path, String expParam, String uidParam, String sigParam) {
        if (path == null || expParam == null || uidParam == null || sigParam == null) return Optional.empty();
        long exp;
        UUID uid;
        try {
            exp = Long.parseLong(expParam);
            uid = UUID.fromString(uidParam);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (exp < clock.instant().getEpochSecond()) return Optional.empty();
        byte[] expected = signature(path, exp, uid).getBytes(StandardCharsets.UTF_8);
        byte[] given = sigParam.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given) ? Optional.of(uid) : Optional.empty();
    }

    private String signature(String path, long exp, UUID userId) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] raw = mac.doFinal((path + "|" + exp + "|" + userId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
