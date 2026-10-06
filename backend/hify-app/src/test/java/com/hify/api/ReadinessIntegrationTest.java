package com.hify.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses production actuator configuration; only the test-owned JDBC connection failure is injected. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-readiness-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "hify.redis.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessIntegrationTest {
    @Autowired MockMvc http;
    @Autowired HealthContributorRegistry contributors;
    @SpyBean DataSource dataSource;

    @Test
    void databaseFailureChangesReadinessButNotLivenessAndRecoveryIsVisible() throws Exception {
        var unavailable = new AtomicBoolean();
        doAnswer(invocation -> {
            if (unavailable.get()) throw new SQLException("synthetic database unavailable: do-not-expose");
            return invocation.callRealMethod();
        }).when(dataSource).getConnection();
        try {
            expectUp("/actuator/health");
            expectUp("/actuator/health/readiness");
            expectUp("/actuator/health/liveness");
            unavailable.set(true);
            // Positive fault control: the existing production db contributor really observed the failure.
            http.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
            String body = http.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("do-not-expose", "jdbc:", "SQLException", "details", "components");
            expectUp("/actuator/health/liveness");
            http.perform(get("/api/v1/health")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("Hify is running"));
            unavailable.set(false);
            expectUp("/actuator/health/readiness");
            expectUp("/actuator/health");
        } finally { unavailable.set(false); }
    }

    @Test
    void optionalContributorFailureDoesNotMakeReadinessFailOrLeakDetails() throws Exception {
        assertThat(contributors.getContributor("redis")).isNull();
        contributors.registerContributor("redis", (HealthIndicator) () ->
                Health.down().withDetail("syntheticCredential", "must-not-appear").build());
        try {
            String root = http.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
            assertThat(root).doesNotContain("must-not-appear", "syntheticCredential", "components", "details");
            expectUp("/actuator/health/readiness");
            expectUp("/actuator/health/liveness");
        } finally { contributors.unregisterContributor("redis"); }
    }

    private void expectUp(String path) throws Exception {
        http.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
    }
}
