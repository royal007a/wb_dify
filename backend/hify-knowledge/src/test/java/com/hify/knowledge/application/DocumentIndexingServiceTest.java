package com.hify.knowledge.application;

import com.hify.knowledge.domain.*;
import com.hify.knowledge.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIndexingServiceTest {
    private final DataSource dataSource=mock(DataSource.class);
    private final Connection connection=mock(Connection.class);
    private final DatabaseMetaData metadata=mock(DatabaseMetaData.class);
    private final PreparedStatement statement=mock(PreparedStatement.class);
    private final KnowledgeDocumentRepository documents=mock(KnowledgeDocumentRepository.class);
    private final KnowledgeBaseRepository bases=mock(KnowledgeBaseRepository.class);
    private final DocumentIndexTaskRepository tasks=mock(DocumentIndexTaskRepository.class);
    private final KnowledgeDocument document=new KnowledgeDocument("doc","kb","a.txt","text/plain",16,"digest","index evidence",Instant.now());
    private final DocumentIndexTask task=new DocumentIndexTask("task","doc",1,Instant.now());
    private DocumentIndexingService service;

    @BeforeEach void prepare() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);
        when(documents.findByIdAndArchivedAtIsNull("doc")).thenReturn(Optional.of(document));
        when(bases.findByIdAndArchivedAtIsNull("kb")).thenReturn(Optional.of(new KnowledgeBase("kb","fixture","",256,16,Instant.now())));
        when(tasks.findByDocumentIdAndDocumentVersion("doc",1)).thenReturn(Optional.of(task));
        service=new DocumentIndexingService(documents,bases,tasks,new JdbcTemplate(dataSource));
    }

    private void indexInTransaction() {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status->service.index("doc"));
    }

    @Test void dialectDetectionDoesNotBorrowOrCloseAnExtraConnection() throws Exception {
        indexInTransaction();
        assertThat(document.getIndexingState()).isEqualTo(DocumentIndexingState.DONE);
        assertThat(task.getState()).isEqualTo("SUCCEEDED");
        verify(metadata).getDatabaseProductName();
        verify(dataSource,times(1)).getConnection();
        verify(connection,times(1)).close(); // Transaction cleanup only.
        verify(connection).commit();
    }

    @Test void metadataFailureCannotDeleteChunksOrMarkIndexSuccessful() throws Exception {
        when(metadata.getDatabaseProductName()).thenThrow(new SQLException("metadata-fixture-private-detail"));
        indexInTransaction();
        assertThat(document.getIndexingState()).isEqualTo(DocumentIndexingState.FAILED);
        assertThat(task.getState()).isEqualTo("FAILED");
        assertThat(document.getErrorMessage()).doesNotContain("metadata-fixture-private-detail");
        verify(connection,never()).prepareStatement(anyString());
        verify(documents,atLeastOnce()).save(document);
        verify(tasks,atLeastOnce()).save(task);
    }

    @Test void unrecognizedDatabaseCannotSilentlyUseH2Storage() throws Exception {
        when(metadata.getDatabaseProductName()).thenReturn("unknown-fixture");
        indexInTransaction();
        assertThat(document.getIndexingState()).isEqualTo(DocumentIndexingState.FAILED);
        assertThat(task.getState()).isEqualTo("FAILED");
        verify(connection,never()).prepareStatement(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"PostgreSQL","H2"})
    void recognizedDatabaseWritesItsExpectedEmbeddingColumns(String product) throws Exception {
        when(metadata.getDatabaseProductName()).thenReturn(product);
        indexInTransaction();
        assertThat(document.getIndexingState()).isEqualTo(DocumentIndexingState.DONE);
        assertThat(task.getState()).isEqualTo("SUCCEEDED");
        assertThat(document.getChunkCount()).isPositive();
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(connection,atLeastOnce()).prepareStatement(sql.capture());
        assertThat(sql.getAllValues()).anySatisfy(value->{
            assertThat(value).contains("INSERT INTO document_chunks", "embedding_text");
            if(product.equals("PostgreSQL"))assertThat(value).contains("embedding,", "CAST(? AS vector)");
            else assertThat(value).doesNotContain("CAST(? AS vector)");
        });
    }
}
