package com.hify.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test void snapshotIsCapturedAtSubmissionAndDoesNotCopySecrets() {
        MDC.put("requestId", "captured");
        MDC.put("authorization", "synthetic-secret");
        Runnable task = RequestLogContext.wrap(() ->
                assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("requestId", "captured")));
        MDC.put("requestId", "worker-previous");
        MDC.put("workerKey", "worker-value");
        var previous = MDC.getCopyOfContextMap();
        task.run();
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
    }

    @Test void exceptionalNestedExecutionRestoresFullPreviousContext() {
        MDC.put("requestId", "outer");
        MDC.put("workerKey", "retained");
        var original = MDC.getCopyOfContextMap();
        Runnable failing = RequestLogContext.wrap(() -> {
            MDC.put("requestId", "inner");
            RequestLogContext.wrap(() -> assertThat(MDC.get("requestId")).isEqualTo("inner")).run();
            MDC.put("leaked", "must-disappear");
            throw new IllegalStateException("synthetic");
        });
        assertThatThrownBy(failing::run).isInstanceOf(IllegalStateException.class).hasMessage("synthetic");
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(original);
    }

    @Test void absentOrInvalidRequestIdDoesNotBorrowWorkerIdentity() {
        for (String value : new String[]{"", "bad\nvalue", "x".repeat(65)}) {
            MDC.put("requestId", value);
            Runnable task = RequestLogContext.wrap(() -> assertThat(MDC.get("requestId")).isNull());
            MDC.put("requestId", "worker");
            task.run();
            assertThat(MDC.get("requestId")).isEqualTo("worker");
        }
        MDC.clear();
        Runnable absent = RequestLogContext.wrap(() -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        MDC.put("requestId", "worker");
        absent.run();
        assertThat(MDC.get("requestId")).isEqualTo("worker");
    }

    @Test void reusedWorkerDoesNotLeakContextAndCancelledFutureNeverRunsBody() throws Exception {
        var pool = (ThreadPoolExecutor) new ThreadPoolConfig().llmExecutor();
        pool.setCorePoolSize(1);
        pool.setMaximumPoolSize(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try {
            var firstWorker = new java.util.concurrent.atomic.AtomicReference<Thread>();
            MDC.put("requestId", "first");
            assertThat(pool.submit(() -> {
                firstWorker.set(Thread.currentThread());
                MDC.put("leaked", "secret");
                return MDC.get("requestId");
            })
                    .get(5, TimeUnit.SECONDS)).isEqualTo("first");
            MDC.put("requestId", "second");
            var secondContext = pool.submit(() -> {
                assertThat(Thread.currentThread()).as("same worker handles both request IDs")
                        .isSameAs(firstWorker.get());
                return MDC.getCopyOfContextMap();
            }).get(5, TimeUnit.SECONDS);
            assertThat(secondContext).as("second request replaces first ID without worker secrets")
                    .containsExactlyEntriesOf(Map.of("requestId", "second"));
            MDC.clear();
            assertThat(pool.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)).isNullOrEmpty();
            pool.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var invoked = new AtomicBoolean();
            var cancelled = pool.submit(() -> invoked.set(true));
            assertThat(cancelled.cancel(true)).isTrue();
            release.countDown();
            assertThat(pool.submit(() -> MDC.get("requestId")).get(5, TimeUnit.SECONDS)).isNull();
            assertThat(invoked).isFalse();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void requestFilterRestoresScopeWhenDownstreamThrows() {
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "bad\nheader");
        MDC.put("requestId", "outer");
        assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
            assertThat(RequestLogContext.valid(MDC.get("requestId"))).isTrue();
            throw new jakarta.servlet.ServletException("synthetic");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get("requestId")).isEqualTo("outer");
        assertThat(response.getHeader("X-Request-Id")).doesNotContain("\n");
    }
}
