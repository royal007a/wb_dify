package com.hify.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WorkflowTerminalMigrationTest {
    @Test void h2UpgradePreservesHistoryAndAllowsOnlyDefinedStates() throws Exception {
        verifyUpgrade("jdbc:h2:mem:workflow-upgrade-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    static void verifyUpgrade(String url, String user, String password) throws Exception {
        Flyway.configure().dataSource(url,user,password).target("20").load().migrate();
        try (Connection db=DriverManager.getConnection(url,user,password); Statement sql=db.createStatement()) {
            sql.executeUpdate("""
                INSERT INTO workflows(id,name,description,schema_version,draft_revision,created_at,updated_at,row_version)
                VALUES ('wf','upgrade','',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """);
            sql.executeUpdate("""
                INSERT INTO workflow_versions(id,workflow_id,version_no,schema_version,dsl_json,checksum,created_at)
                VALUES ('version','wf',1,1,'{"legacy":true}','unchanged-checksum',CURRENT_TIMESTAMP)
                """);
            sql.executeUpdate("""
                INSERT INTO workflow_runs(id,workflow_version_id,workflow_digest,status,input_text,context_json,created_at,row_version)
                VALUES ('run','version','unchanged-checksum','SUCCEEDED','old input','{}',CURRENT_TIMESTAMP,0)
                """);
            sql.executeUpdate("""
                INSERT INTO workflow_node_runs(id,workflow_run_id,sequence_no,node_key,node_type,status,created_at)
                VALUES ('node','run',1,'start','START','SUCCEEDED',CURRENT_TIMESTAMP)
                """);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE workflow_runs SET status='CANCELLED' WHERE id='run'"))
                    .isInstanceOf(SQLException.class);
        }
        Flyway.configure().dataSource(url,user,password).load().migrate();
        try (Connection db=DriverManager.getConnection(url,user,password); Statement sql=db.createStatement()) {
            try (ResultSet row=sql.executeQuery("SELECT status,input_text,workflow_digest FROM workflow_runs WHERE id='run'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("SUCCEEDED");
                assertThat(row.getString(2)).isEqualTo("old input");
                assertThat(row.getString(3)).isEqualTo("unchanged-checksum");
            }
            try (ResultSet row=sql.executeQuery("SELECT dsl_json FROM workflow_versions WHERE id='version'")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("{\"legacy\":true}");
            }
            for (String state : new String[]{"CANCELLED","TIMED_OUT","FAILED","RUNNING","SUCCEEDED"}) {
                assertThat(sql.executeUpdate("UPDATE workflow_runs SET status='"+state+"' WHERE id='run'")).isEqualTo(1);
                assertThat(sql.executeUpdate("UPDATE workflow_node_runs SET status='"+state+"' WHERE id='node'")).isEqualTo(1);
            }
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE workflow_runs SET status='UNKNOWN' WHERE id='run'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE workflow_node_runs SET status='UNKNOWN' WHERE id='node'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
