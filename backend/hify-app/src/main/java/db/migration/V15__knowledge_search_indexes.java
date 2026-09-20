package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL-only derived indexes. H2 keeps embedding_text for contract tests. */
public class V15__knowledge_search_indexes extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName();
        if (!database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) return;
        try (java.sql.Statement statement = context.getConnection().createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("ALTER TABLE document_chunks ADD COLUMN embedding vector(64)");
            statement.execute("UPDATE document_chunks SET embedding = CAST(embedding_text AS vector)");
            statement.execute("CREATE INDEX idx_document_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops)");
            statement.execute("CREATE INDEX idx_document_chunks_fts ON document_chunks USING GIN (to_tsvector('simple', content))");
        }
    }
}
