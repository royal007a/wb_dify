package com.hify.knowledge.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.knowledge.api.KnowledgeChunkResponse;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.knowledge.api.KnowledgeCorpusSnapshot;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public KnowledgeRetrievalService(JdbcTemplate jdbc,KnowledgeApplicationService knowledge){this.jdbc=jdbc;this.knowledge=knowledge;}

    @Override
    @Transactional(readOnly=true)
    public List<KnowledgeCitation> search(String knowledgeBaseId,String query,int topK){
        if(query==null||query.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"检索问题不能为空");
        var base=knowledge.requireBase(knowledgeBaseId); if(!base.isEnabled())throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        return rank(query,topK,activeRows(knowledgeBaseId),knowledgeBaseId,null);
    }

    @Override
    @Transactional
    public KnowledgeCorpusSnapshot freeze(String knowledgeBaseId) {
        var base=knowledge.requireBase(knowledgeBaseId);
        if(!base.isEnabled())throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        List<Row> rows=activeRows(knowledgeBaseId);
        String manifest=manifestDigest(rows);
        List<KnowledgeCorpusSnapshot> existing=jdbc.query("SELECT id,knowledge_base_id,revision_no,manifest_digest,chunk_count FROM knowledge_corpus_versions WHERE knowledge_base_id=? AND manifest_digest=?",
                (rs,n)->new KnowledgeCorpusSnapshot(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getInt(5)),knowledgeBaseId,manifest);
        if(!existing.isEmpty())return existing.get(0);
        Integer revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision_no),0)+1 FROM knowledge_corpus_versions WHERE knowledge_base_id=?",Integer.class,knowledgeBaseId);
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        jdbc.update("INSERT INTO knowledge_corpus_versions(id,knowledge_base_id,revision_no,manifest_digest,chunk_count,created_at) VALUES (?,?,?,?,?,?)",
                id,knowledgeBaseId,revision,manifest,rows.size(),now);
        for(Row row:rows)jdbc.update("INSERT INTO knowledge_corpus_version_chunks(corpus_version_id,chunk_id,content_digest,created_at) VALUES (?,?,?,?)",
                id,row.id(),row.digest(),now);
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
    @Transactional(readOnly=true)
    public List<KnowledgeCitation> searchRevision(String corpusVersionId,String query,int topK) {
        if(query==null||query.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"检索问题不能为空");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_corpus_versions WHERE id=?",Long.class,corpusVersionId);
        if(count==null||count==0)throw new BizException(ErrorCode.NOT_FOUND,"知识语料版本不存在");
        return rank(query,topK,revisionRows(corpusVersionId),null,corpusVersionId);
    }

    private List<KnowledgeCitation> rank(String query,int topK,List<Row> fallbackRows,String baseId,String corpusVersionId){
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
            vector=vector.stream().limit(limit*4L).toList();
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
        List<Row> rows=jdbc.query("SELECT id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text FROM document_chunks WHERE id=? AND archived_at IS NULL",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),chunkId);
        if(rows.isEmpty())throw new BizException(ErrorCode.NOT_FOUND,"引用分块不存在"); Row r=rows.get(0);
        if(expectedDigest!=null&&!expectedDigest.equals(r.digest()))throw new BizException(ErrorCode.CONFLICT,"引用分块摘要不匹配");
        return new KnowledgeChunkResponse(r.id(),r.documentId(),r.version(),r.ordinal(),r.content(),r.digest(),r.tokens());
    }

    @Transactional(readOnly=true)
    public List<KnowledgeChunkResponse> documentChunks(String documentId){
        knowledge.requireDocument(documentId);
        return jdbc.query("SELECT id,document_id,document_version,ordinal,content,content_digest,token_count FROM document_chunks WHERE document_id=? AND archived_at IS NULL ORDER BY ordinal",(rs,n)->new KnowledgeChunkResponse(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7)),documentId);
    }

    private List<Row> activeRows(String baseId){return jdbc.query("SELECT id,document_id,document_version,ordinal,content,content_digest,token_count,embedding_text FROM document_chunks WHERE knowledge_base_id=? AND archived_at IS NULL ORDER BY id",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),baseId);}
    private List<Row> revisionRows(String versionId){return jdbc.query("SELECT c.id,c.document_id,c.document_version,c.ordinal,c.content,c.content_digest,c.token_count,c.embedding_text FROM knowledge_corpus_version_chunks vc JOIN document_chunks c ON c.id=vc.chunk_id WHERE vc.corpus_version_id=? AND c.content_digest=vc.content_digest ORDER BY c.id",(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),versionId);}
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
            ORDER BY embedding <=> CAST(? AS vector) LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),baseId,literal,limit);}
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
            ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?
            """,(rs,n)->new Row(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)),versionId,literal,limit);}
    private String manifestDigest(List<Row> rows){StringBuilder canonical=new StringBuilder();for(Row row:rows)canonical.append(row.id()).append(':').append(row.digest()).append('\n');try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    private boolean isPostgres(){try(var connection=jdbc.getDataSource().getConnection()){return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");}catch(Exception e){return false;}}
    private void add(Map<String,Double>s,Map<String,Row>rows,List<Row>ranked,double weight){for(int i=0;i<ranked.size();i++){Row r=ranked.get(i);rows.put(r.id(),r);s.merge(r.id(),weight/(RRF_K+i+1),Double::sum);}}
    private double lexicalScore(String query,String text){String q=normalize(query),t=normalize(text);if(q.isBlank())return 0;if(t.contains(q))return 10+q.length();double score=0;for(int i=0;i<q.length()-1;i++){if(t.contains(q.substring(i,i+2)))score++;}return score;}
    private String normalize(String value){return value.toLowerCase(Locale.ROOT).replaceAll("\\s+","").replaceAll("[^\\p{L}\\p{N}]","");}
    private record Row(String id,String documentId,int version,int ordinal,String content,String digest,int tokens,String embedding){}
}
