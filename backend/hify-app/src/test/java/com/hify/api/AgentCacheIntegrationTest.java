package com.hify.api;

import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentToolBindingRequest;
import com.hify.agent.api.AgentUpdateRequest;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(AgentCacheIntegrationTest.TestCacheConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-agent-cache-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class AgentCacheIntegrationTest {
    @Autowired AgentService management;
    @Autowired AgentQueryService queries;
    @Autowired CacheManager cacheManager;

    @Test
    void draftChangesDoNotEvictRuntimeSnapshotsButPublishAndArchiveEvictCurrentOnly() {
        String agentId = management.create(new AgentUpsertRequest(
                "Cache Boundary Agent", "v1", "help", "mock", "hify-mock",
                0.2, 2048, 6, 10, List.of("calculator"), true));
        String versionOne = management.publish(agentId).id();

        var currentV1 = queries.requirePublished(agentId);
        var immutableV1 = queries.requireVersion(versionOne);
        Cache cache = cacheManager.getCache("agent-cache");
        assertThat(cache).isNotNull();
        assertThat(cache.get("current:" + agentId)).isNotNull();
        assertThat(cache.get("version:" + versionOne)).isNotNull();

        management.update(agentId, new AgentUpdateRequest(
                "Cache Boundary Agent", "v2", "help differently", "mock", "hify-mock",
                0.4, 2048, 6, 10, true));
        management.replaceTools(agentId, new AgentToolBindingRequest(List.of("current_time")));

        assertThat(cache.get("current:" + agentId, currentV1.getClass())).isEqualTo(currentV1);
        assertThat(cache.get("version:" + versionOne, immutableV1.getClass())).isEqualTo(immutableV1);
        assertThat(management.get(agentId).hasUnpublishedChanges()).isTrue();

        String versionTwo = management.publish(agentId).id();
        assertThat(cache.get("current:" + agentId)).isNull();
        assertThat(cache.get("version:" + versionOne)).isNotNull();
        var currentV2 = queries.requirePublished(agentId);
        var immutableV2 = queries.requireVersion(versionTwo);
        assertThat(currentV2.versionId()).isEqualTo(versionTwo);
        assertThat(management.get(agentId).hasUnpublishedChanges()).isFalse();

        management.archive(agentId);
        assertThat(cache.get("current:" + agentId)).isNull();
        assertThat(cache.get("version:" + versionOne, immutableV1.getClass())).isEqualTo(immutableV1);
        assertThat(cache.get("version:" + versionTwo, immutableV2.getClass())).isEqualTo(immutableV2);
        assertThat(queries.requireVersion(versionOne)).isEqualTo(immutableV1);
        assertThatThrownBy(() -> queries.requirePublished(agentId)).isInstanceOf(BizException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCacheConfiguration {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager("agent-cache");
        }
    }
}
