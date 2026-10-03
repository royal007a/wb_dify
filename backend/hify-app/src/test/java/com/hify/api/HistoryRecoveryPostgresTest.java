package com.hify.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker=true)
class HistoryRecoveryPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("history_recovery").withUsername("hify").withPassword("hify");
    @org.junit.jupiter.api.Test void v22HistorySurvivesV23Upgrade() throws Exception {
        HistoryRecoveryMigrationTest.verifyUpgrade(POSTGRES.getJdbcUrl(),"hify","hify");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) @org.junit.jupiter.api.Timeout(60)
    void realRestartReplaysCanonicalOperations(boolean firstTool) throws Exception {
        String database=firstTool?"history_with_tool":"history_only_model";
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),"hify","hify");
            var statement=connection.createStatement()){statement.execute("CREATE DATABASE "+database);}
        HistoryRecoveryIntegrationTest.verifyRecovery(POSTGRES.getJdbcUrl().replace("/history_recovery","/"+database),firstTool);
    }
}
