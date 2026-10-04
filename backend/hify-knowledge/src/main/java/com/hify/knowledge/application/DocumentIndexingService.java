package com.hify.knowledge.application;

import com.hify.knowledge.domain.DocumentIndexTask;
import com.hify.knowledge.domain.KnowledgeBase;
import com.hify.knowledge.domain.KnowledgeDocument;
import com.hify.knowledge.infrastructure.DocumentIndexTaskRepository;
import com.hify.knowledge.infrastructure.KnowledgeBaseRepository;
import com.hify.knowledge.infrastructure.KnowledgeDocumentRepository;
import org.springframework.jdbc.core.ConnectionCallback;
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
import java.util.UUID;

@Service
public class DocumentIndexingService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeBaseRepository bases;
    private final DocumentIndexTaskRepository tasks;
    private final JdbcTemplate jdbc;
    private final RecursiveTextChunker chunker = new RecursiveTextChunker();
    @org.springframework.beans.factory.annotation.Autowired private SemanticEmbeddings semantic;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public DocumentIndexingService(KnowledgeDocumentRepository documents, KnowledgeBaseRepository bases,
                                   DocumentIndexTaskRepository tasks, JdbcTemplate jdbc) {
        this.documents=documents; this.bases=bases; this.tasks=tasks; this.jdbc=jdbc;
    }

    @Async("asyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndexRequested(KnowledgeIndexRequested event) { index(event.documentId()); }

    void index(String documentId) {
        KnowledgeDocument document=documents.findByIdAndArchivedAtIsNull(documentId).orElse(null);
        if(document==null) return;
        DocumentIndexTask task=tasks.findByDocumentIdAndDocumentVersion(documentId,document.getDocumentVersion()).orElseThrow();
        KnowledgeBase base=bases.findByIdAndArchivedAtIsNull(document.getKnowledgeBaseId()).orElseThrow();
        List<RecursiveTextChunker.Chunk> chunks;
        List<float[]> semanticVectors=new java.util.ArrayList<>();
        // Provider IO runs before the write transaction: no DB connection is held while embedding.
        try {
            chunks=chunker.split(document.getCanonicalContent(),base.getChunkSize(),base.getChunkOverlap());
            if(base.getEmbeddingProfile()!=null){
                if(chunks.size()>512)throw new IllegalArgumentException("语义索引超过512个分块，请拆分知识库");
                for(int i=0;i<chunks.size();i+=32)
                    semanticVectors.addAll(semantic.embed(base.getEmbeddingProfile(),chunks.subList(i,Math.min(chunks.size(),i+32)).stream().map(RecursiveTextChunker.Chunk::content).toList()));
            }
        }catch(Exception e){
            writeTransaction(()->{document.failed("语义索引失败，请检查Embedding配置和Provider");task.failed("语义索引失败，请检查Embedding配置和Provider");documents.save(document);tasks.save(task);});return;
        }
        writeTransaction(()->persist(documentId,document,task,base,chunks,semanticVectors));
    }

    private void writeTransaction(Runnable action){
        if(transactionManager==null){action.run();return;}
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status->action.run());
    }

    private void persist(String documentId,KnowledgeDocument document,DocumentIndexTask task,KnowledgeBase base,
                         List<RecursiveTextChunker.Chunk> chunks,List<float[]> semanticVectors){
        try {
            if(documents.findByIdAndArchivedAtIsNull(documentId).isEmpty()||bases.findByIdAndArchivedAtIsNull(base.getId()).isEmpty())return;
            if(base.getEmbeddingProfile()!=null){
                // Serialize admission with other indexes and snapshot publication.
                jdbc.queryForObject("SELECT id FROM knowledge_bases WHERE id=? FOR UPDATE",String.class,base.getId());
                Long existing=jdbc.queryForObject("SELECT COUNT(*) FROM document_chunks WHERE knowledge_base_id=? AND archived_at IS NULL AND document_id<>?",Long.class,base.getId(),documentId);
                if(existing+chunks.size()>512)throw new IllegalArgumentException("精确语义索引上限为512个活动分块，请拆分知识库");
            }
            task.running("hify-local"); document.processing(); tasks.save(task); documents.save(document);
            if(chunks.isEmpty()) throw new IllegalArgumentException("文档没有可索引文本");
            boolean postgres=isPostgres();
            jdbc.update("DELETE FROM document_chunks WHERE document_id = ? AND document_version = ?",documentId,document.getDocumentVersion());
            int semanticIndex=0;
            for(RecursiveTextChunker.Chunk chunk:chunks){
                float[] embedding=KnowledgeEmbedding.embed(chunk.content()); String literal=KnowledgeEmbedding.literal(embedding);
                String id=UUID.randomUUID().toString(); String digest=digest(chunk.content());
                if(postgres){
                    jdbc.update("""
                            INSERT INTO document_chunks(id,knowledge_base_id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text,embedding,created_at)
                            VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS vector),?)
                            """,id,base.getId(),documentId,document.getDocumentVersion(),chunk.ordinal(),chunk.content(),digest,chunk.tokenCount(),literal,literal,java.sql.Timestamp.from(Instant.now()));
                }else{
                    jdbc.update("""
                            INSERT INTO document_chunks(id,knowledge_base_id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text,created_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?)
                            """,id,base.getId(),documentId,document.getDocumentVersion(),chunk.ordinal(),chunk.content(),digest,chunk.tokenCount(),literal,java.sql.Timestamp.from(Instant.now()));
                }
                if(base.getEmbeddingProfile()!=null)jdbc.update("UPDATE document_chunks SET semantic_profile=?,semantic_vector=? WHERE id=?",
                        base.getEmbeddingProfile(),KnowledgeEmbedding.literal(semanticVectors.get(semanticIndex++)),id);
                task.checkpoint(chunk.ordinal());
            }
            document.indexed(chunks.size()); task.succeeded(); documents.save(document); tasks.save(task);
        } catch(Exception exception){
            String message=safe(exception); document.failed(message); task.failed(message); documents.save(document); tasks.save(task);
        }
    }

    private boolean isPostgres() {
        try {
            // JdbcTemplate reuses the transaction-bound connection; never borrow a second one
            // while holding the indexing transaction, or infer H2 from a metadata failure.
            return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
                String product=connection.getMetaData().getDatabaseProductName();
                if("PostgreSQL".equalsIgnoreCase(product))return true;
                if("H2".equalsIgnoreCase(product))return false;
                throw new IllegalStateException("Unsupported indexing database");
            });
        } catch(Exception failure) {
            // This message is persisted and returned by document management APIs.
            throw new IllegalStateException("无法确认索引数据库类型",failure);
        }
    }
    private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String safe(Exception exception){String value=exception.getMessage();if(value==null||value.isBlank())value=exception.getClass().getSimpleName();return value.substring(0,Math.min(900,value.length()));}
}
