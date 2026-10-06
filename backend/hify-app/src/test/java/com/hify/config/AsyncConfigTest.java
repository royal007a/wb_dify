package com.hify.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {
    @Test void productionRunExecutorPropagatesCorrelationAndRemainsBounded() throws Exception {
        var executor = (ThreadPoolTaskExecutor) new AsyncConfig().runExecutor();
        try {
            MDC.put("requestId", "http-to-run");
            MDC.put("syntheticSecret", "not-forwarded");
            var context = executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS);
            assertThat(context).containsOnlyKeys("requestId").containsEntry("requestId", "http-to-run");
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
        } finally { MDC.clear(); executor.shutdown(); }
    }
}
