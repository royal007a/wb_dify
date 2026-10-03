package com.hify.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class HistoryRecoveryMigrationTest {
    @Test void legacyCanonicalHistoryIsNotRewritten() throws Exception {
        verifyUpgrade("jdbc:h2:mem:history-upgrade-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
    }
    static void verifyUpgrade(String url,String user,String password) throws Exception {
        Flyway.configure().dataSource(url,user,password).target("22").load().migrate();
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()){
            sql.executeUpdate("INSERT INTO providers(public_id,name,type,base_url,auth_config,default_model_id,enabled,created_at,updated_at) VALUES('p','legacy','MOCK','','{}','m',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO agent_definitions(id,name,instructions,provider_id,temperature,max_turns,enabled) VALUES('a','legacy','fixture','p',0.2,3,true)");
            sql.executeUpdate("INSERT INTO conversations(id,agent_id,title,created_at,updated_at) VALUES('c','a','legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO agent_runs(id,conversation_id,idempotency_key,request_hash,state,input_message,turns,tool_calls,created_at,updated_at,version) VALUES('r','c','legacy','hash','RUNNING','fixture',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
            sql.executeUpdate("INSERT INTO run_history_commits(run_id,revision,operation_id,semantic_digest,messages_json,committed_at) VALUES('r',1,'model:1','legacy-digest','[]',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,user,password).load().migrate();
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement();
            var row=sql.executeQuery("SELECT revision,operation_id,semantic_digest,messages_json,recovery_json,recovery_digest FROM run_history_commits WHERE run_id='r'")){
            assertThat(row.next()).isTrue();assertThat(row.getLong(1)).isEqualTo(1);
            assertThat(row.getString(2)).isEqualTo("model:1");assertThat(row.getString(3)).isEqualTo("legacy-digest");
            assertThat(row.getString(4)).isEqualTo("[]");assertThat(row.getString(5)).isNull();assertThat(row.getString(6)).isNull();
            assertThat(row.next()).isFalse();
        }
        try(var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()){
            assertThatThrownBy(()->sql.executeUpdate("UPDATE run_history_commits SET recovery_json='{}' WHERE run_id='r'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
