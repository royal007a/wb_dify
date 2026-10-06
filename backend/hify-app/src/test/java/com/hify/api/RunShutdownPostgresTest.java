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
    @Test @Timeout(60) void computedResultIsCommittedOnShutdownWithoutReplayOnPostgres() throws Exception {
        // Different database: the assertions count this fixture's messages only.
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),"hify","hify");
            var statement=connection.createStatement()) {statement.execute("CREATE DATABASE shutdown_completed");}
        RunShutdownIntegrationTest.verifyComputedResultShutdown(POSTGRES.getJdbcUrl().replace("/shutdown_test","/shutdown_completed"));
    }
    @Test @Timeout(60) void expiredInterruptedRunCannotRestartItsBudgetOnPostgres() throws Exception {
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),"hify","hify");
            var statement=connection.createStatement()) {statement.execute("CREATE DATABASE shutdown_expired");}
        RunShutdownIntegrationTest.verifyExpiredModelShutdownRecovery(POSTGRES.getJdbcUrl().replace("/shutdown_test","/shutdown_expired"));
    }
}
