package com.smartstaff.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("generates an id when the caller sends none")
    void generatesId() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/jobs"));

        assertThat(response.getHeader("X-Request-Id")).matches("[A-Za-z0-9-]{8}");
    }

    @Test
    @DisplayName("echoes back a sane caller-supplied id so a request can be traced across systems")
    void echoesSafeId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");
        request.addHeader("X-Request-Id", "trace-42.abc_DEF");

        assertThat(run(request).getHeader("X-Request-Id")).isEqualTo("trace-42.abc_DEF");
    }

    @Test
    @DisplayName("replaces a caller-supplied id that could forge or corrupt log lines")
    void replacesUnsafeIds() throws Exception {
        for (String unsafe : new String[]{"has spaces", "inject\nfake log line", "<script>", "x".repeat(65), ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");
            request.addHeader("X-Request-Id", unsafe);

            assertThat(run(request).getHeader("X-Request-Id")).isNotEqualTo(unsafe).matches("[A-Za-z0-9-]{8}");
        }
    }

    @Test
    @DisplayName("the id is in the logging MDC while the request runs, and gone afterwards")
    void mdcLifecycle() throws Exception {
        AtomicReference<String> seenInside = new AtomicReference<>();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");
        request.addHeader("X-Request-Id", "req-1");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInside.set(MDC.get("requestId")));

        assertThat(seenInside.get()).isEqualTo("req-1");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the request blows up")
    void mdcClearedOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/jobs");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("invite tokens in the URL path are masked before anything is logged")
    void redactsInviteTokens() {
        assertThat(RequestIdFilter.redact("/api/interview/by_token/s3cr3t-token")).isEqualTo("/api/interview/by_token/***");
        assertThat(RequestIdFilter.redact("/api/interview/save_by_token/s3cr3t-token")).isEqualTo("/api/interview/save_by_token/***");
        assertThat(RequestIdFilter.redact("/api/jobs/123")).isEqualTo("/api/jobs/123");
    }
}
