package com.hify.knowledge.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageResult;
import com.hify.knowledge.api.KnowledgeBaseRequest;
import com.hify.knowledge.api.KnowledgeBaseResponse;
import com.hify.knowledge.api.KnowledgeDocumentResponse;
import com.hify.knowledge.domain.DocumentIndexTask;
import com.hify.knowledge.domain.DocumentIndexingState;
import com.hify.knowledge.domain.KnowledgeBase;
import com.hify.knowledge.domain.KnowledgeDocument;
import com.hify.knowledge.infrastructure.DocumentIndexTaskRepository;
import com.hify.knowledge.infrastructure.KnowledgeBaseRepository;
import com.hify.knowledge.infrastructure.KnowledgeDocumentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class KnowledgeApplicationService {
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final DocumentIndexTaskRepository tasks;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public KnowledgeApplicationService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                       DocumentIndexTaskRepository tasks, JdbcTemplate jdbc,
                                       ApplicationEventPublisher events) {
        this.bases=bases; this.documents=documents; this.tasks=tasks; this.jdbc=jdbc; this.events=events;
    }

    @Transactional
    public String create(KnowledgeBaseRequest request) {
        String name=request.name().trim(); if(bases.existsByNameAndArchivedAtIsNull(name)) throw duplicate();
        int size=request.chunkSize()==null?512:request.chunkSize(); int overlap=request.chunkOverlap()==null?64:request.chunkOverlap();
        validateChunking(size,overlap);
        KnowledgeBase base=new KnowledgeBase(UUID.randomUUID().toString(),name,clean(request.description()),size,overlap,Instant.now());
        try { bases.saveAndFlush(base); } catch(DataIntegrityViolationException e){throw duplicate();}
        return base.getId();
    }

    @Transactional(readOnly=true)
    public PageResult<KnowledgeBaseResponse> list(Integer page,Integer pageSize,String keyword){
        int p=page==null?1:Math.max(1,page); int s=pageSize==null?20:Math.min(100,Math.max(1,pageSize));
        var result=bases.findByArchivedAtIsNullAndNameContainingIgnoreCase(keyword==null?"":keyword.trim(),
                PageRequest.of(p-1,s, Sort.by(Sort.Direction.DESC,"updatedAt")));
        List<KnowledgeBaseResponse> rows=result.getContent().stream().map(this::response).toList();
        return PageResult.of(rows,result.getTotalElements(),p,s);
    }

    @Transactional(readOnly=true) public KnowledgeBaseResponse get(String id){return response(requireBase(id));}

    @Transactional
    public void update(String id,KnowledgeBaseRequest request){
        KnowledgeBase base=requireBase(id); String name=request.name().trim();
        if(bases.existsByNameAndIdNotAndArchivedAtIsNull(name,id)) throw duplicate();
        int size=request.chunkSize()==null?base.getChunkSize():request.chunkSize();
        int overlap=request.chunkOverlap()==null?base.getChunkOverlap():request.chunkOverlap(); validateChunking(size,overlap);
        base.update(name,clean(request.description()),size,overlap,request.enabled()==null?base.isEnabled():request.enabled()); bases.save(base);
    }

    @Transactional
    public void archiveBase(String id){
        KnowledgeBase base=requireBase(id); documents.findByKnowledgeBaseIdAndArchivedAtIsNull(id).forEach(KnowledgeDocument::archive);
        jdbc.update("UPDATE document_chunks SET archived_at = ? WHERE knowledge_base_id = ? AND archived_at IS NULL",Instant.now(),id);
        base.archive(); bases.save(base);
    }

    @Transactional
    public String upload(String baseId,MultipartFile file){
        KnowledgeBase base=requireBase(baseId); if(!base.isEnabled()) throw new BizException(ErrorCode.CONFLICT,"知识库已停用");
        if(file==null||file.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR,"文件不能为空");
        if(file.getSize()>MAX_FILE_SIZE) throw new BizException(ErrorCode.PARAM_ERROR,"文件不能超过 10MB");
        String name=file.getOriginalFilename()==null?"document.txt":file.getOriginalFilename();
        String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!List.of("txt","md","markdown").contains(extension)) throw new BizException(ErrorCode.PARAM_ERROR,"仅支持 TXT/Markdown");
        byte[] bytes;
        try{bytes=file.getBytes();}catch(Exception e){throw new BizException(ErrorCode.PARAM_ERROR,"读取上传文件失败");}
        String content=decodeUtf8(bytes); if(content.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR,"文档内容不能为空");
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        KnowledgeDocument document=new KnowledgeDocument(id,baseId,name,file.getContentType()==null?"text/plain":file.getContentType(),
                bytes.length,digest(bytes),content,now);
        documents.save(document); tasks.save(new DocumentIndexTask(UUID.randomUUID().toString(),id,1,now));
        events.publishEvent(new KnowledgeIndexRequested(id)); return id;
    }

    @Transactional(readOnly=true)
    public PageResult<KnowledgeDocumentResponse> documents(String baseId,Integer page,Integer pageSize){
        requireBase(baseId); int p=page==null?1:Math.max(1,page); int s=pageSize==null?20:Math.min(100,Math.max(1,pageSize));
        var result=documents.findByKnowledgeBaseIdAndArchivedAtIsNull(baseId,PageRequest.of(p-1,s,Sort.by(Sort.Direction.DESC,"createdAt")));
        return PageResult.of(result.getContent().stream().map(this::documentResponse).toList(),result.getTotalElements(),p,s);
    }
    @Transactional(readOnly=true) public KnowledgeDocumentResponse document(String id){return documentResponse(requireDocument(id));}

    @Transactional
    public void archiveDocument(String id){
        KnowledgeDocument document=requireDocument(id);
        if(document.getIndexingState()==DocumentIndexingState.PROCESSING) throw new BizException(ErrorCode.CONFLICT,"文档正在处理，不能归档");
        jdbc.update("UPDATE document_chunks SET archived_at = ? WHERE document_id = ? AND archived_at IS NULL",Instant.now(),id);
        document.archive(); documents.save(document);
    }

    KnowledgeBase requireBase(String id){return bases.findByIdAndArchivedAtIsNull(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"知识库不存在"));}
    KnowledgeDocument requireDocument(String id){return documents.findByIdAndArchivedAtIsNull(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"文档不存在"));}
    private KnowledgeBaseResponse response(KnowledgeBase b){return new KnowledgeBaseResponse(b.getId(),b.getName(),b.getDescription(),b.getChunkSize(),b.getChunkOverlap(),b.isEnabled(),documents.findByKnowledgeBaseIdAndArchivedAtIsNull(b.getId()).size(),b.getCreatedAt(),b.getUpdatedAt());}
    private KnowledgeDocumentResponse documentResponse(KnowledgeDocument d){return new KnowledgeDocumentResponse(d.getId(),d.getKnowledgeBaseId(),d.getName(),d.getMediaType(),d.getFileSize(),d.getChecksum(),d.getDocumentVersion(),d.getIndexingState().name(),d.getErrorMessage(),d.getChunkCount(),d.getCreatedAt(),d.getUpdatedAt());}
    private void validateChunking(int size,int overlap){if(overlap<0||overlap>=size)throw new BizException(ErrorCode.PARAM_ERROR,"chunkOverlap 必须小于 chunkSize");}
    private BizException duplicate(){return new BizException(ErrorCode.CONFLICT,"知识库名称已存在");}
    private String clean(String value){return value==null?"":value.trim();}
    private String decodeUtf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new BizException(ErrorCode.PARAM_ERROR,"文档必须是 UTF-8 编码");}}
    private String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
