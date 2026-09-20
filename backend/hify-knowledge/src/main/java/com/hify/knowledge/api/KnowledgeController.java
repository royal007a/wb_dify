package com.hify.knowledge.api;

import com.hify.common.PageResult;
import com.hify.common.Result;
import com.hify.knowledge.application.KnowledgeApplicationService;
import com.hify.knowledge.application.KnowledgeRetrievalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
    private final KnowledgeApplicationService knowledge;
    private final KnowledgeRetrievalService retrieval;
    public KnowledgeController(KnowledgeApplicationService knowledge,KnowledgeRetrievalService retrieval){this.knowledge=knowledge;this.retrieval=retrieval;}

    @PostMapping("/knowledge-bases") public ResponseEntity<Result<String>> create(@Valid @RequestBody KnowledgeBaseRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(knowledge.create(request)));}
    @GetMapping("/knowledge-bases") public PageResult<KnowledgeBaseResponse> list(@RequestParam(required=false) @Min(1) Integer page,@RequestParam(required=false) @Min(1) @Max(100) Integer pageSize,@RequestParam(required=false) String keyword){return knowledge.list(page,pageSize,keyword);}
    @GetMapping("/knowledge-bases/{id}") public Result<KnowledgeBaseResponse> get(@PathVariable String id){return Result.ok(knowledge.get(id));}
    @PutMapping("/knowledge-bases/{id}") public Result<Void> update(@PathVariable String id,@Valid @RequestBody KnowledgeBaseRequest request){knowledge.update(id,request);return Result.ok();}
    @DeleteMapping("/knowledge-bases/{id}") public Result<Void> archive(@PathVariable String id){knowledge.archiveBase(id);return Result.ok();}
    @PostMapping(value="/knowledge-bases/{id}/documents",consumes="multipart/form-data") public ResponseEntity<Result<String>> upload(@PathVariable String id,@RequestPart("file") MultipartFile file){return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(knowledge.upload(id,file)));}
    @GetMapping("/knowledge-bases/{id}/documents") public PageResult<KnowledgeDocumentResponse> documents(@PathVariable String id,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer pageSize){return knowledge.documents(id,page,pageSize);}
    @GetMapping("/documents/{id}") public Result<KnowledgeDocumentResponse> document(@PathVariable String id){return Result.ok(knowledge.document(id));}
    @GetMapping("/documents/{id}/chunks") public Result<List<KnowledgeChunkResponse>> chunks(@PathVariable String id){return Result.ok(retrieval.documentChunks(id));}
    @DeleteMapping("/documents/{id}") public Result<Void> archiveDocument(@PathVariable String id){knowledge.archiveDocument(id);return Result.ok();}
    @PostMapping("/knowledge-bases/{id}/retrieval-tests") public Result<List<KnowledgeCitation>> retrieval(@PathVariable String id,@Valid @RequestBody RetrievalTestRequest request){return Result.ok(retrieval.search(id,request.query(),request.topK()==null?3:request.topK()));}
}
