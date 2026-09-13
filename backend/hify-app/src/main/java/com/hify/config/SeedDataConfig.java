package com.hify.config;

import com.hify.domain.AgentDefinition;
import com.hify.agent.api.AgentService;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.provider.api.ProviderBootstrapService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeedDataConfig {
    @Bean
    CommandLineRunner seedDemo(ProviderBootstrapService providers, AgentDefinitionRepository agents,
                               AgentService agentService) {
        return args -> {
            providers.ensureDevelopmentMock();
            if (!agents.existsById("demo-agent")) {
                agents.save(new AgentDefinition(
                        "demo-agent",
                        "Demo Agent",
                        "无需 API Key 的本地演示 Agent",
                        "你是 Hify Demo Agent。回答要准确、简洁；遇到时间和算术问题优先使用工具。",
                        "mock",
                        "hify-mock",
                        0.2,
                        6,
                        "current_time,calculator",
                        true
                ));
            }
            AgentDefinition demo = agents.findById("demo-agent").orElseThrow();
            if (demo.getPublishedVersionId() == null) agentService.publish("demo-agent");
        };
    }
}
