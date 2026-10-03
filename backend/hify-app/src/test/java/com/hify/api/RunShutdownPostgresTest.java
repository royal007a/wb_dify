package com.hify.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker=true)
class RunShutdownPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("shutdown_test").withUsername("hify").withPassword("hify");
    @Test @Timeout(60) void realContextShutdownAndRestartOnPostgres() throws Exception {
        RunShutdownIntegrationTest.verifyModelShutdownRecovery(POSTGRES.getJdbcUrl());
    }
}
