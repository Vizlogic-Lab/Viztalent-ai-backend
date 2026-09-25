package com.smartstaff.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** Validates Twilio's X-Twilio-Signature header on inbound webhooks, per
 *  SMARTSTAFF_BACKEND_DESIGN.md ("must validate the Twilio request
 *  signature") and Twilio's documented algorithm: HMAC-SHA1(authToken,
 *  fullUrl + sortedParamKey1 + paramValue1 + sortedParamKey2 + ...),
 *  base64-encoded.
 *
 *  `fullUrl` MUST be the URL exactly as Twilio itself requested it — the
 *  public tunnel URL, not whatever Spring sees behind a reverse proxy —
 *  which is why callers pass it explicitly (built from
 *  SettingsService.getPublicBaseUrl() + the request path) rather than
 *  reading it off HttpServletRequest.
 *
 *  Unverified in this build — see docs/FEATURES.md's Phase 8 notes: nothing
 *  here can be exercised without a real Twilio account signing a real
 *  request against a real public tunnel. */
@Component
public class TwilioSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA1";

    public boolean isValid(String authToken, String fullUrl, Map<String, String> params, String signatureHeader) {
        if (authToken == null || authToken.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            StringBuilder data = new StringBuilder(fullUrl);
            for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
                data.append(entry.getKey()).append(entry.getValue() == null ? "" : entry.getValue());
            }

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] computed = mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(computed);

            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
