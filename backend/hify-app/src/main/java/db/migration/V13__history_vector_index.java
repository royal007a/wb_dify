package db.migration;

import com.hify.memory.LocalHistoryEmbedding;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds pgvector storage, backfills existing derived refs, and creates a cosine HNSW index. */
public class V13__history_vector_index extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName();
        if (!database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) return;
        try (java.sql.Statement statement = context.getConnection().createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("ALTER TABLE history_detail_refs ADD COLUMN embedding vector(64)");
        }
        try (java.sql.PreparedStatement read = context.getConnection().prepareStatement(
                "SELECT id, search_text FROM history_detail_refs");
             java.sql.ResultSet rows = read.executeQuery();
             java.sql.PreparedStatement update = context.getConnection().prepareStatement(
                     "UPDATE history_detail_refs SET embedding = CAST(? AS vector) WHERE id = ?")) {
            while (rows.next()) {
                update.setString(1, LocalHistoryEmbedding.postgresLiteral(
                        LocalHistoryEmbedding.embed(rows.getString(2))));
                update.setString(2, rows.getString(1));
                update.addBatch();
            }
            update.executeBatch();
        }
        try (java.sql.Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE INDEX idx_detail_ref_embedding_hnsw
                        ON history_detail_refs USING hnsw (embedding vector_cosine_ops)
                    """);
        }
    }
}
