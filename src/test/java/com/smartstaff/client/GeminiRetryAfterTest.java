package com.smartstaff.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiRetryAfterTest {

    @Test
    void retryAfterSecondsAreHonoured() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "7");
        assertThat(GeminiClient.retryAfterMs(h)).isEqualTo(7000L);
        h.set(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(GeminiClient.retryAfterMs(h)).isNull();   // date form: our own backoff
        assertThat(GeminiClient.retryAfterMs(new HttpHeaders())).isNull();
        assertThat(GeminiClient.retryAfterMs(null)).isNull();
    }
}
