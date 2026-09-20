package com.hify.knowledge.application;

import com.hify.knowledge.domain.DocumentIndexTask;
import com.hify.knowledge.domain.KnowledgeBase;
import com.hify.knowledge.domain.KnowledgeDocument;
import com.hify.knowledge.infrastructure.DocumentIndexTaskRepository;
import com.hify.knowledge.infrastructure.KnowledgeBaseRepository;
import com.hify.knowledge.infrastructure.KnowledgeDocumentRepository;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentIndexingService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final DocumentIndexTaskRepository tasks;
    private final JdbcTemplate jdbc;
    private final RecursiveTextChunker chunker = new RecursiveTextChunker();

    public DocumentIndexingService(KnowledgeDocumentRepository documents, KnowledgeBaseRepository bases,
                                   DocumentIndexTaskRepository tasks, JdbcTemplate jdbc) {
        this.documents=documents; this.bases=bases; this.tasks=tasks; this.jdbc=jdbc;
    }

    @Async("asyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndexRequested(KnowledgeIndexRequested event) { index(event.documentId()); }

    void index(String documentId) {
        KnowledgeDocument document=documents.findByIdAndArchivedAtIsNull(documentId).orElse(null);
        if(document==null) return;
        DocumentIndexTask task=tasks.findByDocumentIdAndDocumentVersion(documentId,document.getDocumentVersion()).orElseThrow();
        KnowledgeBase base=bases.findByIdAndArchivedAtIsNull(document.getKnowledgeBaseId()).orElseThrow();
        try {
            task.running("hify-local"); document.processing(); tasks.save(task); documents.save(document);
            List<RecursiveTextChunker.Chunk> chunks=chunker.split(document.getCanonicalContent(),base.getChunkSize(),base.getChunkOverlap());
            if(chunks.isEmpty()) throw new IllegalArgumentException("文档没有可索引文本");
            jdbc.update("DELETE FROM document_chunks WHERE document_id = ? AND document_version = ?",documentId,document.getDocumentVersion());
            boolean postgres=isPostgres();
            for(RecursiveTextChunker.Chunk chunk:chunks){
                float[] embedding=KnowledgeEmbedding.embed(chunk.content()); String literal=KnowledgeEmbedding.literal(embedding);
                String id=UUID.randomUUID().toString(); String digest=digest(chunk.content());
                if(postgres){
                    jdbc.update("""
                            INSERT INTO document_chunks(id,knowledge_base_id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text,embedding,created_at)
                            VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS vector),?)
                            """,id,base.getId(),documentId,document.getDocumentVersion(),chunk.ordinal(),chunk.content(),digest,chunk.tokenCount(),literal,literal,Instant.now());
                }else{
                    jdbc.update("""
                            INSERT INTO document_chunks(id,knowledge_base_id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text,created_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?)
                            """,id,base.getId(),documentId,document.getDocumentVersion(),chunk.ordinal(),chunk.content(),digest,chunk.tokenCount(),literal,Instant.now());
                }
                task.checkpoint(chunk.ordinal());
            }
            document.indexed(chunks.size()); task.succeeded(); documents.save(document); tasks.save(task);
        } catch(Exception exception){
            String message=safe(exception); document.failed(message); task.failed(message); documents.save(document); tasks.save(task);
        }
    }

    private boolean isPostgres(){try(var connection=jdbc.getDataSource().getConnection()){return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");}catch(Exception e){return false;}}
    private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String safe(Exception exception){String value=exception.getMessage();if(value==null||value.isBlank())value=exception.getClass().getSimpleName();return value.substring(0,Math.min(900,value.length()));}
}
