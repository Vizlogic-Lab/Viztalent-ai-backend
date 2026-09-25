package com.smartstaff.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwilioSignatureValidatorTest {

    // The worked example from Twilio's own "Validating Requests" documentation
    // (also the fixture in twilio-java's RequestValidatorTest) — so this checks the
    // algorithm against Twilio's spec, not just against our own implementation.
    private static final String AUTH_TOKEN = "12345";
    private static final String URL = "https://mycompany.com/myapp.php?foo=1&bar=2";
    private static final String SIGNATURE = "RSOYDt4T1cUTdK1PDd93/VVr8B8=";

    private static Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("Digits", "1234");
        p.put("To", "+18005551212");
        p.put("From", "+14158675309");
        p.put("Caller", "+14158675309");
        p.put("CallSid", "CA1234567890ABCDE");
        return p;
    }

    private final TwilioSignatureValidator validator = new TwilioSignatureValidator();

    @Test
    @DisplayName("accepts Twilio's published reference request")
    void acceptsReferenceVector() {
        assertThat(validator.isValid(AUTH_TOKEN, URL, params(), SIGNATURE)).isTrue();
    }

    @Test
    @DisplayName("parameter order in the map doesn't matter — they're sorted by key before signing")
    void orderIndependent() {
        Map<String, String> reversed = new LinkedHashMap<>();
        params().entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(e -> reversed.put(e.getKey(), e.getValue()));
        assertThat(validator.isValid(AUTH_TOKEN, URL, reversed, SIGNATURE)).isTrue();
    }

    @Test
    @DisplayName("rejects a request whose parameters were tampered with")
    void rejectsTamperedParam() {
        Map<String, String> p = params();
        p.put("Digits", "9999");
        assertThat(validator.isValid(AUTH_TOKEN, URL, p, SIGNATURE)).isFalse();
    }

    @Test
    @DisplayName("rejects a request with an extra or missing parameter")
    void rejectsExtraOrMissingParam() {
        Map<String, String> extra = params();
        extra.put("Injected", "x");
        Map<String, String> missing = params();
        missing.remove("Digits");
        assertThat(validator.isValid(AUTH_TOKEN, URL, extra, SIGNATURE)).isFalse();
        assertThat(validator.isValid(AUTH_TOKEN, URL, missing, SIGNATURE)).isFalse();
    }

    @Test
    @DisplayName("rejects a signature computed for a different URL (e.g. localhost instead of the public URL)")
    void rejectsWrongUrl() {
        assertThat(validator.isValid(AUTH_TOKEN, "http://localhost:8000/myapp.php?foo=1&bar=2", params(), SIGNATURE)).isFalse();
    }

    @Test
    @DisplayName("rejects the wrong auth token")
    void rejectsWrongToken() {
        assertThat(validator.isValid("54321", URL, params(), SIGNATURE)).isFalse();
    }

    @Test
    @DisplayName("rejects garbage, blank and missing signatures, and a missing token, without throwing")
    void rejectsMalformedInput() {
        assertThat(validator.isValid(AUTH_TOKEN, URL, params(), "not-a-signature")).isFalse();
        assertThat(validator.isValid(AUTH_TOKEN, URL, params(), "")).isFalse();
        assertThat(validator.isValid(AUTH_TOKEN, URL, params(), null)).isFalse();
        assertThat(validator.isValid(null, URL, params(), SIGNATURE)).isFalse();
        assertThat(validator.isValid("", URL, params(), SIGNATURE)).isFalse();
    }
}
