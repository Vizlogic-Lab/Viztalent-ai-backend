package com.smartstaff.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadSignatureServiceTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-000";
    private static final Pattern PARAMS = Pattern.compile("\\?exp=(\\d+)&uid=([^&]+)&sig=(.+)$");

    private static Matcher params(String url) {
        Matcher m = PARAMS.matcher(url);
        assertThat(m.find()).isTrue();
        return m;
    }

    @Test
    void validWithinTtlAndInvalidAfter() {
        Instant t0 = Instant.parse("2026-09-30T10:00:00Z");
        UUID user = UUID.randomUUID();
        String url = new DownloadSignatureService(SECRET, Clock.fixed(t0, ZoneOffset.UTC)).sign("/api/download_report", user);
        Matcher m = params(url);

        var atExpiry = new DownloadSignatureService(SECRET, Clock.fixed(t0.plusSeconds(300), ZoneOffset.UTC));
        var afterExpiry = new DownloadSignatureService(SECRET, Clock.fixed(t0.plusSeconds(301), ZoneOffset.UTC));
        assertThat(atExpiry.verify("/api/download_report", m.group(1), m.group(2), m.group(3))).contains(user);
        assertThat(afterExpiry.verify("/api/download_report", m.group(1), m.group(2), m.group(3))).isEmpty();
    }

    @Test
    void signatureBindsPathUserAndSecret() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        var service = new DownloadSignatureService(SECRET, clock);
        UUID user = UUID.randomUUID();
        Matcher m = params(service.sign("/api/download_report", user));

        assertThat(service.verify("/api/scorecard/x/1", m.group(1), m.group(2), m.group(3))).isEmpty();
        assertThat(service.verify("/api/download_report", m.group(1), UUID.randomUUID().toString(), m.group(3))).isEmpty();
        assertThat(new DownloadSignatureService(SECRET + "x", clock)
                .verify("/api/download_report", m.group(1), m.group(2), m.group(3))).isEmpty();
        assertThat(service.verify("/api/download_report", "abc", m.group(2), m.group(3))).isEmpty();
        assertThat(service.verify("/api/download_report", m.group(1), "not-a-uuid", m.group(3))).isEmpty();
    }
}
