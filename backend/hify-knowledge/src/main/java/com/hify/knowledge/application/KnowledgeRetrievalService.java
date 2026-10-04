package com.hify.knowledge.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.TextInput;
import com.hify.knowledge.api.KnowledgeChunkResponse;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.knowledge.api.KnowledgeCorpusSnapshot;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class KnowledgeRetrievalService implements KnowledgeRetrievalPort {
    private static final int RRF_K=60;
    private final JdbcTemplate jdbc;
    private final KnowledgeApplicationService knowledge;

    private final double minVectorScore;
    @org.springframework.beans.factory.annotation.Autowired private SemanticEmbeddings semantic;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public KnowledgeRetrievalService(JdbcTemplate jdbc,KnowledgeApplicationService knowledge,
            @org.springframework.beans.factory.annotation.Value("${hify.knowledge.min-vector-score:0.5}") double minVectorScore){
        this.jdbc=jdbc;this.knowledge=knowledge;
        if(!Double.isFinite(minVectorScore)||minVectorScore < -1 || minVectorScore > 1)
            throw new IllegalArgumentException("Knowledge vector candidate threshold must be finite and within [-1,1]");
        this.minVectorScore=minVectorScore;
    }

    @Override
    public List<KnowledgeCitation> search(String knowledgeBaseId,String query,int topK){
        TextInput.requireNoNul(knowledgeBaseId, query);
        if(query==null||query.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"检索问题不能为空");
        var base=knowledge.requireBase(knowledgeBaseId); if(!base.isEnabled())throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        var vectors=prepareVectors(query,knowledgeBaseId,null);
        return readTransaction(()->rank(query,topK,activeRows(knowledgeBaseId),knowledgeBaseId,null,vectors));
    }

    @Override
    @Transactional
    public KnowledgeCorpusSnapshot freeze(String knowledgeBaseId) {
        // Serialize version allocation and observe the current base state, not a cached JPA entity.
        List<Boolean> enabled=jdbc.query("SELECT enabled FROM knowledge_bases WHERE id=? AND archived_at IS NULL FOR UPDATE",
                (rs,n)->rs.getBoolean(1),knowledgeBaseId);
        if(enabled.isEmpty())throw new BizException(ErrorCode.NOT_FOUND,"知识库不存在");
        if(!enabled.get(0))throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        List<Row> rows=activeRows(knowledgeBaseId);
        rows.forEach(this::verifyContent);
        String manifest=manifestDigest(rows);
        List<KnowledgeCorpusSnapshot> existing=jdbc.query("SELECT id,knowledge_base_id,revision_no,manifest_digest,chunk_count FROM knowledge_corpus_versions WHERE knowledge_base_id=? AND manifest_digest=?",
                (rs,n)->new KnowledgeCorpusSnapshot(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getInt(5)),knowledgeBaseId,manifest);
        if(!existing.isEmpty()){verifiedRows(existing.get(0));return existing.get(0);}
        Integer revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision_no),0)+1 FROM knowledge_corpus_versions WHERE knowledge_base_id=?",Integer.class,knowledgeBaseId);
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        jdbc.update("INSERT INTO knowledge_corpus_versions(id,knowledge_base_id,revision_no,manifest_digest,chunk_count,created_at) VALUES (?,?,?,?,?,?)",
                id,knowledgeBaseId,revision,manifest,rows.size(),java.sql.Timestamp.from(now));
        for(Row row:rows)jdbc.update("INSERT INTO knowledge_corpus_version_chunks(corpus_version_id,chunk_id,content_digest,created_at) VALUES (?,?,?,?)",
                id,row.id(),row.digest(),java.sql.Timestamp.from(now));
        return new KnowledgeCorpusSnapshot(id,knowledgeBaseId,revision,manifest,rows.size());
    }

    @Override
    @Transactional(readOnly=true)
    public KnowledgeCorpusSnapshot currentSnapshot(String knowledgeBaseId) {
        var base=knowledge.requireBase(knowledgeBaseId);
        if(!base.isEnabled())throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        List<Row> rows=activeRows(knowledgeBaseId);
        return new KnowledgeCorpusSnapshot("",knowledgeBaseId,0,manifestDigest(rows),rows.size());
    }

    @Override
    public List<KnowledgeCitation> searchRevision(String corpusVersionId,String query,int topK) {
        TextInput.requireNoNul(corpusVersionId, query);
        if(query==null||query.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"检索问题不能为空");
        var vectors=prepareVectors(query,null,corpusVersionId);
        return readTransaction(()->{KnowledgeCorpusSnapshot snapshot=requireSnapshot(corpusVersionId);
            return rank(query,topK,verifiedRows(snapshot),null,corpusVersionId,vectors);});
    }

    @Override
    public List<KnowledgeCitation> searchSnapshot(KnowledgeCorpusSnapshot expected,String query,int topK) {
        if(expected==null)throw new BizException(ErrorCode.CONFLICT,"缺少发布知识快照，请重新发布");
        TextInput.requireNoNul(expected.id(), expected.knowledgeBaseId(), expected.manifestDigest(), query);
        if(query==null||query.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"检索问题不能为空");
        var vectors=prepareVectors(query,null,expected.id());
        return readTransaction(()->{KnowledgeCorpusSnapshot actual=requireSnapshot(expected.id());
            if(!actual.equals(expected))throw new BizException(ErrorCode.CONFLICT,"知识语料快照摘要或身份不匹配");
            return rank(query,topK,verifiedRows(actual),null,actual.id(),vectors);});
    }

    private KnowledgeCorpusSnapshot requireSnapshot(String id) {
        var snapshots=jdbc.query("SELECT id,knowledge_base_id,revision_no,manifest_digest,chunk_count FROM knowledge_corpus_versions WHERE id=?",
                (rs,n)->new KnowledgeCorpusSnapshot(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getInt(5)),id);
        if(snapshots.isEmpty())throw new BizException(ErrorCode.NOT_FOUND,"知识语料版本不存在");
        return snapshots.get(0);
    }

    private List<Row> verifiedRows(KnowledgeCorpusSnapshot snapshot) {
        List<Row> rows=revisionRows(snapshot.id());
        if(rows.size()!=snapshot.chunkCount()||!manifestDigest(rows).equals(snapshot.manifestDigest()))
            throw new BizException(ErrorCode.CONFLICT,"知识语料清单或摘要不完整");
        rows.forEach(this::verifyContent);
        return rows;
    }

    private void verifyContent(Row row) {
        if(!sha256(row.content()).equals(row.digest()))throw new BizException(ErrorCode.CONFLICT,"引用分块原文摘要不匹配");
    }

    private List<KnowledgeCitation> rank(String query,int topK,List<Row> fallbackRows,String baseId,String corpusVersionId,Map<String,float[]> queryVectors){
        int limit=Math.min(20,Math.max(1,topK)); float[] queryVector=KnowledgeEmbedding.embed(query);
        List<Row> lexical; List<Row> vector;
        if(isPostgres()){
            lexical=corpusVersionId==null?postgresLexical(baseId,query,limit*4):postgresRevisionLexical(corpusVersionId,query,limit*4);
            vector=corpusVersionId==null?postgresVector(baseId,queryVector,limit*4):postgresRevisionVector(corpusVersionId,queryVector,limit*4);
        }else{
            List<Row> all=fallbackRows;
            lexical=new ArrayList<>(all); lexical.sort(Comparator.comparingDouble((Row r)->lexicalScore(query,r.content())).reversed());
            lexical=lexical.stream().filter(r->lexicalScore(query,r.content())>0).limit(limit*4L).toList();
            vector=new ArrayList<>(all);
            vector.sort(Comparator.comparingDouble((Row r)->KnowledgeEmbedding.cosine(queryVector,KnowledgeEmbedding.parse(r.embedding()))).reversed());
            vector=vector.stream().filter(r->KnowledgeEmbedding.cosine(queryVector,KnowledgeEmbedding.parse(r.embedding()))>=minVectorScore)
                    .limit(limit*4L).toList();
        }
        Map<String,SemanticRow> semanticRows=semanticRows(fallbackRows);
        if(!semanticRows.isEmpty()){
            Map<String,Double> similarity=new HashMap<>();
            for(Row row:fallbackRows){
                SemanticRow stored=semanticRows.get(row.id());
                float[] candidate=KnowledgeEmbedding.parse(stored==null?row.embedding():stored.vector());
                float[] q=stored==null?queryVector:queryVectors.get(stored.profile());
                if(q==null)throw new BizException(ErrorCode.CONFLICT,"Embedding 空间在检索期间变化，请重试");
                if(q.length!=candidate.length)throw new BizException(ErrorCode.CONFLICT,"知识语义向量维度不匹配");
                double score=KnowledgeEmbedding.cosine(q,candidate);
                if(!Double.isFinite(score))throw new BizException(ErrorCode.CONFLICT,"知识语义向量不可用");
                similarity.put(row.id(),score);
            }
            vector=fallbackRows.stream().filter(r->similarity.get(r.id())>=minVectorScore)
                    .sorted(Comparator.comparingDouble((Row r)->similarity.get(r.id())).reversed().thenComparing(Row::id))
                    .limit(limit*4L).toList();
        }
        Map<String,Double> scores=new HashMap<>(); Map<String,Row> rows=new LinkedHashMap<>();
        add(scores,rows,lexical,.45); add(scores,rows,vector,.55);
        List<Map.Entry<String,Double>> ranked=scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(limit).toList();
        List<KnowledgeCitation> out=new ArrayList<>(); int rank=1;
        for(var item:ranked){Row r=rows.get(item.getKey());out.add(new KnowledgeCitation(r.id(),r.documentId(),r.version(),r.ordinal(),r.content(),r.digest(),item.getValue(),rank++));}
        return out;
    }

    @Override
    @Transactional(readOnly=true)
    public KnowledgeChunkResponse requireCanonicalChunk(String chunkId,String expectedDigest){
        List<Row> rows=jdbc.query("SELECT c.id,c.document_id,c.document_version,c.ordinal,c.content,c.content_digest,c.token_count,c.embedding_text FROM document_chunks c WHERE c.id=? AND (c.archived_at IS NULL OR EXISTS (SELECT 1 FROM knowledge_corpus_version_chunks vc WHERE vc.chunk_id=c.id AND vc.content_digest=c.content_digest AND vc.content_digest=?))",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),chunkId,expectedDigest);
        if(rows.isEmpty())throw new BizException(ErrorCode.NOT_FOUND,"引用分块不存在"); Row r=rows.get(0);
        if(expectedDigest!=null&&!expectedDigest.equals(r.digest()))throw new BizException(ErrorCode.CONFLICT,"引用分块摘要不匹配");
        verifyContent(r);
        return new KnowledgeChunkResponse(r.id(),r.documentId(),r.version(),r.ordinal(),r.content(),r.digest(),r.tokens());
    }

    @Transactional(readOnly=true)
    public List<KnowledgeChunkResponse> documentChunks(String documentId){
        knowledge.requireDocument(documentId);
        return jdbc.query("SELECT id,document_id,document_version,ordinal,content,content_digest,token_count FROM document_chunks WHERE document_id=? AND archived_at IS NULL ORDER BY ordinal",(rs,n)->new KnowledgeChunkResponse(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7)),documentId);
    }

    private List<Row> activeRows(String baseId){return jdbc.query("SELECT id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text FROM document_chunks WHERE knowledge_base_id=? AND archived_at IS NULL ORDER BY id",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),baseId);}
    private List<Row> revisionRows(String versionId){return jdbc.query("SELECT c.id,c.document_id,c.document_version,c.ordinal,c.content,c.content_digest,c.token_count,c.embedding_text FROM knowledge_corpus_version_chunks vc JOIN document_chunks c ON c.id=vc.chunk_id JOIN knowledge_corpus_versions v ON v.id=vc.corpus_version_id WHERE vc.corpus_version_id=? AND c.content_digest=vc.content_digest AND c.knowledge_base_id=v.knowledge_base_id ORDER BY c.id",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),versionId);}
    private List<Row> postgresLexical(String baseId,String query,int limit){return jdbc.query("""
            SELECT id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text
            FROM document_chunks
            WHERE knowledge_base_id=? AND archived_at IS NULL
              AND (to_tsvector('simple',content) @@ websearch_to_tsquery('simple',?) OR lower(content) LIKE lower(?))
            ORDER BY ts_rank_cd(to_tsvector('simple',content),websearch_to_tsquery('simple',?)) DESC, ordinal
            LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),baseId,query,"%"+query+"%",query,limit);}
    private List<Row> postgresVector(String baseId,float[] query,int limit){String literal=KnowledgeEmbedding.literal(query);return jdbc.query("""
            SELECT id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text
            FROM document_chunks WHERE knowledge_base_id=? AND archived_at IS NULL
              AND (embedding <=> CAST(? AS vector)) <= ?
            ORDER BY embedding <=> CAST(? AS vector) LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),baseId,literal,1-minVectorScore,literal,limit);}
    private List<Row> postgresRevisionLexical(String versionId,String query,int limit){return jdbc.query("""
            SELECT c.id,c.document_id,c.document_version,c.ordinal,c.content,c.content_digest,c.token_count,c.embedding_text
            FROM knowledge_corpus_version_chunks vc JOIN document_chunks c ON c.id=vc.chunk_id
            WHERE vc.corpus_version_id=? AND c.content_digest=vc.content_digest
              AND (to_tsvector('simple',c.content) @@ websearch_to_tsquery('simple',?) OR lower(c.content) LIKE lower(?))
            ORDER BY ts_rank_cd(to_tsvector('simple',c.content),websearch_to_tsquery('simple',?)) DESC,c.ordinal LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),versionId,query,"%"+query+"%",query,limit);}
    private List<Row> postgresRevisionVector(String versionId,float[] query,int limit){String literal=KnowledgeEmbedding.literal(query);return jdbc.query("""
            SELECT c.id,c.document_id,c.document_version,c.ordinal,c.content,c.content_digest,c.token_count,c.embedding_text
            FROM knowledge_corpus_version_chunks vc JOIN document_chunks c ON c.id=vc.chunk_id
            WHERE vc.corpus_version_id=? AND c.content_digest=vc.content_digest
              AND (c.embedding <=> CAST(? AS vector)) <= ?
            ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),versionId,literal,1-minVectorScore,literal,limit);}
    private String manifestDigest(List<Row> rows){
        Map<String,SemanticRow> semanticRows=semanticRows(rows);
        StringBuilder canonical=new StringBuilder();
        for(Row row:rows){canonical.append(row.id()).append(':').append(row.digest()).append('\n');
            var stored=semanticRows.get(row.id());
            if(stored!=null)canonical.append("semantic:").append(sha256(stored.profile()+"\n"+stored.vector())).append('\n');
        }
        return sha256(canonical.toString());
    }
    private Map<String,SemanticRow> semanticRows(List<Row> rows){
        Map<String,SemanticRow> result=new HashMap<>();
        for(int from=0;from<rows.size();from+=500){
            var batch=rows.subList(from,Math.min(rows.size(),from+500));
            String placeholders=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));
            jdbc.query("SELECT id,semantic_profile,semantic_vector FROM document_chunks WHERE semantic_profile IS NOT NULL AND id IN ("+placeholders+")",
                    rs->{result.put(rs.getString(1),new SemanticRow(rs.getString(2),rs.getString(3)));},batch.stream().map(Row::id).toArray());
        }
        return result;
    }
    private record SemanticRow(String profile,String vector){}
    private Map<String,float[]> prepareVectors(String query,String baseId,String corpusId){
        List<String> profiles=corpusId==null
                ?jdbc.queryForList("SELECT DISTINCT semantic_profile FROM document_chunks WHERE knowledge_base_id=? AND archived_at IS NULL AND semantic_profile IS NOT NULL",String.class,baseId)
                :jdbc.queryForList("SELECT DISTINCT c.semantic_profile FROM document_chunks c JOIN knowledge_corpus_version_chunks vc ON vc.chunk_id=c.id WHERE vc.corpus_version_id=? AND c.semantic_profile IS NOT NULL",String.class,corpusId);
        if(!profiles.isEmpty()&&org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new BizException(ErrorCode.CONFLICT,"语义检索须在数据库事务外发起");
        Map<String,float[]> vectors=new HashMap<>();
        for(String profile:profiles)vectors.put(profile,semantic.embed(profile,List.of(query)).get(0));
        return vectors;
    }
    private <T> T readTransaction(java.util.function.Supplier<T> read){
        if(transactionManager==null)return read.get();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status->read.get());
    }
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable",failure);}}
    private boolean isPostgres(){return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
            connection->connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql")));}
    private void add(Map<String,Double>s,Map<String,Row>rows,List<Row>ranked,double weight){for(int i=0;i<ranked.size();i++){Row r=ranked.get(i);rows.put(r.id(),r);s.merge(r.id(),weight/(RRF_K+i+1),Double::sum);}}
    private double lexicalScore(String query,String text){String q=normalize(query),t=normalize(text);if(q.isBlank())return 0;if(t.contains(q))return 10+q.length();double score=0;for(int i=0;i<q.length()-1;i++){if(t.contains(q.substring(i,i+2)))score++;}return score;}
    private String normalize(String value){return value.toLowerCase(Locale.ROOT).replaceAll("\\s+","").replaceAll("[^\\p{L}\\p{N}]","");}
    private record Row(String id,String documentId,int version,int ordinal,String content,String digest,int tokens,String embedding){}
}
