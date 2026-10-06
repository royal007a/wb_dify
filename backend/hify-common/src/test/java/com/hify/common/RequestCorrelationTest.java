package com.hify.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationTest {
    @AfterEach void clearContext() { MDC.clear(); }

    @Test void configuredLlmPoolCarriesRequestId() throws Exception {
        var pool = (ThreadPoolExecutor) new ThreadPoolConfig().llmExecutor();
        try {
            MDC.put("requestId", "request-A");
            assertThat(pool.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS))
                    .isEqualTo("request-A");
        } finally { pool.shutdownNow(); }
    }

    @Test void configuredAsyncPoolCarriesRequestId() throws Exception {
        var pool = (ThreadPoolExecutor) new ThreadPoolConfig().asyncExecutor();
        try {
            MDC.put("requestId", "request-B");
            assertThat(pool.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS))
                    .isEqualTo("request-B");
        } finally { pool.shutdownNow(); }
    }

    @Test void requestFilterRestoresEnclosingScope() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "request-inner");
        MDC.put("requestId", "request-outer");
        new RequestLoggingFilter().doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isEqualTo("request-inner"));
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("request-inner");
        assertThat(MDC.get("requestId")).isEqualTo("request-outer");
    }
}
