package com.hify.workflow.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import java.util.concurrent.*;

@Configuration
public class WorkflowExecutionConfiguration {
    @Bean(name = "workflowIoExecutor", destroyMethod = "shutdown")
    Executor workflowIoExecutor() {
        return new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32),
                new CustomizableThreadFactory("workflow-io-"), new ThreadPoolExecutor.AbortPolicy());
    }
}
