package com.hify.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolBindingMigrationTest {
    @Test
    void upgradesLegacyToolListsAndEnforcesAgentNameUniqueness() throws Exception {
        String url = "jdbc:h2:mem:agent-tool-upgrade-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("6").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO providers (
                        public_id, name, type, base_url, auth_config, default_model_id,
                        enabled, created_at, updated_at, deleted
                    ) VALUES (
                        'provider-1', 'Provider 1', 'OPENAI_COMPATIBLE', 'https://example.invalid',
                        '{"version":1}', 'model-1', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                    )
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_definitions (
                        id, name, description, instructions, provider_id, model, temperature,
                        max_turns, enabled_tools, enabled
                    ) VALUES (
                        'agent-1', 'Legacy Agent', 'legacy', 'help', 'provider-1', 'model-1',
                        0.2, 6, 'calculator,current_time,calculator', TRUE
                    )
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_versions (
                        id, agent_id, version_no, name, description, instructions, provider_id,
                        model, temperature, max_tokens, max_turns, max_context_turns,
                        enabled_tools, enabled, snapshot_digest, created_at
                    ) VALUES (
                        'version-1', 'agent-1', 1, 'Legacy Agent', 'legacy', 'help', 'provider-1',
                        'model-1', 0.2, 2048, 6, 10, 'calculator,current_time,calculator', TRUE,
                        'legacy-digest', CURRENT_TIMESTAMP
                    )
                    """);
            connection.createStatement().executeUpdate("""
                    UPDATE agent_definitions SET published_version_id = 'version-1' WHERE id = 'agent-1'
                    """);
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(names(connection, "agent_tool_bindings", "agent_id", "agent-1"))
                    .containsExactly("calculator", "current_time");
            assertThat(names(connection, "agent_version_tool_bindings", "agent_version_id", "version-1"))
                    .containsExactly("calculator", "current_time");
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    INSERT INTO agent_definitions (
                        id, name, instructions, provider_id, model, temperature, max_turns, enabled
                    ) VALUES ('agent-2', 'Legacy Agent', 'duplicate', 'provider-1', 'model-1', 0.2, 6, TRUE)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UQ_AGENT_DEFINITION_NAME");
        }
    }

    private List<String> names(Connection connection, String table, String ownerColumn,
                               String ownerId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tool_name FROM " + table + " WHERE " + ownerColumn + " = ? ORDER BY tool_name")) {
            statement.setString(1, ownerId);
            try (var rows = statement.executeQuery()) {
                var result = new java.util.ArrayList<String>();
                while (rows.next()) result.add(rows.getString(1));
                return result;
            }
        }
    }
}
