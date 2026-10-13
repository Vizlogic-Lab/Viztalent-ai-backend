package com.smartstaff.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which X-Forwarded-For entry the rate limiter treats as the client. */
class RateLimitFilterClientAddressTest {

    @Test
    @DisplayName("one trusted proxy: the rightmost entry, ignoring whatever the caller put in front")
    void oneProxyTakesRightmost() {
        assertThat(RateLimitFilter.clientFromForwarded("203.0.113.5", 1)).isEqualTo("203.0.113.5");
        assertThat(RateLimitFilter.clientFromForwarded("1.2.3.4, 203.0.113.5", 1)).isEqualTo("203.0.113.5");
        assertThat(RateLimitFilter.clientFromForwarded("forged, also-forged , 203.0.113.5", 1)).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("two trusted proxies (e.g. a CDN in front of Traefik): second from the right")
    void twoProxies() {
        assertThat(RateLimitFilter.clientFromForwarded("forged, 203.0.113.5, 10.0.0.1", 2)).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("a chain shorter than the proxy count falls back to its leftmost entry")
    void shortChain() {
        assertThat(RateLimitFilter.clientFromForwarded("203.0.113.5", 3)).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("missing, blank or empty-entry headers yield no address (the socket address is used instead)")
    void unusableHeader() {
        assertThat(RateLimitFilter.clientFromForwarded(null, 1)).isNull();
        assertThat(RateLimitFilter.clientFromForwarded("  ", 1)).isNull();
        assertThat(RateLimitFilter.clientFromForwarded(" , ,", 1)).isNull();
    }

    @Test
    @DisplayName("a proxy count below 1 is treated as 1")
    void nonPositiveProxyCount() {
        assertThat(RateLimitFilter.clientFromForwarded("forged, 203.0.113.5", 0)).isEqualTo("203.0.113.5");
    }
}
