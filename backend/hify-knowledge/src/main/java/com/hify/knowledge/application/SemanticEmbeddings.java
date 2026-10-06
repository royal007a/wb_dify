package com.hify.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import com.hify.knowledge.api.KnowledgeEmbeddingConfig;
import com.hify.provider.api.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;

@Component
public class SemanticEmbeddings {
    private final EmbeddingService service;
    private final ObjectMapper json;
    private final ExecutionLifecycle lifecycle;
    public SemanticEmbeddings(EmbeddingService service,ObjectMapper json,ExecutionLifecycle lifecycle){this.service=service;this.json=json;this.lifecycle=lifecycle;}
    public String freeze(KnowledgeEmbeddingConfig config){
        if(config==null)return null;
        if(config.dimensions()==null)throw new BizException(ErrorCode.PARAM_ERROR,"Embedding 维度必填");
        try{return json.writeValueAsString(service.freeze(config.providerId(),config.model(),config.dimensions()));}
        catch(BizException e){throw e;}catch(Exception e){throw invalid();}
    }
    public KnowledgeEmbeddingConfig config(String stored){
        if(stored==null)return null;var p=read(stored);return new KnowledgeEmbeddingConfig(p.providerId(),p.model(),p.dimensions());
    }
    public List<float[]> embed(String stored,List<String> text){
        return embed(stored,text,ExecutionControl.none());
    }
    public List<float[]> embed(String stored,List<String> text,ExecutionControl parent){
        var control=parent.boundedBy(Duration.ofSeconds(45)).withShutdown(lifecycle::isStopping);
        check(parent,control);
        try {
            var result=service.embed(read(stored),text,control);
            check(parent,control);
            return result;
        } catch(RuntimeException failure) {
            check(parent,control);
            throw failure;
        }
    }
    private void check(ExecutionControl parent,ExecutionControl child){
        child.throwIfCancelled();
        parent.checkActive();
        // A child-only timeout is a dependency failure, not expiration of the Run.
        if(child.isExpired())throw new LlmApiException(LlmApiException.Type.TIMEOUT,"Embedding attempt timed out");
    }
    private EmbeddingProfile read(String value){try{return json.readValue(value,EmbeddingProfile.class);}catch(Exception e){throw invalid();}}
    private BizException invalid(){return new BizException(ErrorCode.CONFLICT,"Embedding 配置不可用");}
}
