package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL gets a real GIN FTS index; H2 remains a contract-test fallback. */
public class V12__history_full_text_index extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName();
        if (!database.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) return;
        try (java.sql.Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE INDEX idx_detail_ref_fts
                        ON history_detail_refs USING GIN (
                            to_tsvector('simple', COALESCE(search_text, '') || ' ' || COALESCE(keywords_text, ''))
                        )
                    """);
        }
    }
}
