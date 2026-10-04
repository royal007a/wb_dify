package com.hify.api;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ReadInputHygienePostgresTest extends ReadInputHygieneIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("read_input_hygiene").withUsername("hify").withPassword("hify");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @BeforeEach void realPostgresRejectsNul(){
        assertThat(db.queryForObject("select version()",String.class)).contains("PostgreSQL");
        assertThatThrownBy(()->db.queryForObject("select cast(? as text)",String.class,"a"+NUL+"b"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
