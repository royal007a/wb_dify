package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Moves the legacy comma-separated tool lists into normalized binding tables. */
public class V8__backfill_agent_tool_bindings extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        backfill(connection, "agent_definitions", "id", "agent_tool_bindings", "agent_id");
        backfill(connection, "agent_versions", "id", "agent_version_tool_bindings", "agent_version_id");
    }

    private void backfill(Connection connection, String sourceTable, String sourceId,
                          String targetTable, String targetId) throws SQLException {
        String select = "SELECT " + sourceId + ", enabled_tools FROM " + sourceTable
                + " WHERE enabled_tools IS NOT NULL";
        String insert = "INSERT INTO " + targetTable + " (" + targetId
                + ", tool_name, created_at) VALUES (?, ?, ?)";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(select);
             PreparedStatement binding = connection.prepareStatement(insert)) {
            while (rows.next()) {
                String ownerId = rows.getString(1);
                for (String toolName : split(rows.getString(2))) {
                    binding.setString(1, ownerId);
                    binding.setString(2, toolName);
                    binding.setTimestamp(3, Timestamp.from(Instant.now()));
                    binding.addBatch();
                }
            }
            binding.executeBatch();
        }
    }

    private Set<String> split(String value) {
        Set<String> names = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return names;
        for (String item : value.split(",")) {
            if (!item.isBlank()) names.add(item.trim());
        }
        return names;
    }
}
