package com.hify.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonContractsTest {
    @Test
    void saturatedLlmPoolNeverRunsBlockingWorkOnCaller() throws Exception {
        var pool = (ThreadPoolExecutor) new ThreadPoolConfig().llmExecutor();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inline = new AtomicBoolean();
        // Keep the configured queue/rejection policy, use one worker to exercise saturation cheaply.
        pool.setCorePoolSize(1);
        pool.setMaximumPoolSize(1);
        try {
            pool.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            while (pool.getQueue().remainingCapacity() > 0) pool.execute(() -> {});
            assertThatThrownBy(() -> pool.execute(() -> inline.set(true))).isInstanceOf(RejectedExecutionException.class);
            assertThat(inline).isFalse();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void resultAndPageResultUseOneErrorCodeContract() {
        Result<String> success = Result.ok("value");
        Result<Void> failure = Result.fail(ErrorCode.PARAM_ERROR);
        PageResult<String> page = PageResult.of(List.of("one"), 1, 2, 10);

        assertThat(success.getCode()).isEqualTo(ErrorCode.OK.code());
        assertThat(success.getData()).isEqualTo("value");
        assertThat(failure.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.code());
        assertThat(failure.getMessage()).isEqualTo(ErrorCode.PARAM_ERROR.message());
        assertThat(page.getData()).containsExactly("one");
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    void businessExceptionAllowsMessageOverrideWithoutChangingCode() {
        BizException exception = new BizException(ErrorCode.CONFLICT, "Run already closed");
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("Run already closed");
    }

    @Test
    void pageHelperNormalizesUnsafeClientInput() {
        var defaults = PageHelper.toPage(null, null);
        var clamped = PageHelper.toPage(0, 999);

        assertThat(defaults.getCurrent()).isEqualTo(1);
        assertThat(defaults.getSize()).isEqualTo(20);
        assertThat(clamped.getCurrent()).isEqualTo(1);
        assertThat(clamped.getSize()).isEqualTo(100);
    }

    @Test
    void threadPoolsAreBoundedAndNamedByWorkload() throws Exception {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolExecutor llm = (ThreadPoolExecutor) config.llmExecutor();
        ThreadPoolExecutor async = (ThreadPoolExecutor) config.asyncExecutor();
        try {
            assertThat(llm.getCorePoolSize()).isEqualTo(10);
            assertThat(llm.getMaximumPoolSize()).isEqualTo(50);
            assertThat(llm.getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(llm.submit(() -> Thread.currentThread().getName()).get()).startsWith("llm-");
            assertThat(async.getCorePoolSize()).isEqualTo(5);
            assertThat(async.getMaximumPoolSize()).isEqualTo(20);
            assertThat(async.getQueue().remainingCapacity()).isEqualTo(200);
            assertThat(async.submit(() -> Thread.currentThread().getName()).get()).startsWith("async-");
        } finally {
            llm.shutdownNow();
            async.shutdownNow();
        }
    }
}
