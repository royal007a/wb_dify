package com.hify.provider.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import com.hify.provider.api.*;
import com.hify.provider.runtime.CredentialResolver;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ProviderEmbeddingService implements EmbeddingService {
    private final ProviderQueryService providers;
    private final ProviderUrlPolicy urls;
    private final ProviderAuthConfigCodec auth;
    private final CredentialResolver credentials;
    private final LlmHttpClient http;
    private final CircuitBreakerService breaker;
    private final ObjectMapper json;
    public ProviderEmbeddingService(ProviderQueryService providers, ProviderUrlPolicy urls,
            ProviderAuthConfigCodec auth, CredentialResolver credentials, LlmHttpClient http,
            CircuitBreakerService breaker, ObjectMapper json) {
        this.providers=providers;this.urls=urls;this.auth=auth;this.credentials=credentials;
        this.http=http;this.breaker=breaker;this.json=json;
    }
    public EmbeddingProfile freeze(String providerId,String model,int dimensions) {
        TextInput.requireNoNul(providerId,model);
        if(model==null||model.isBlank()||model.length()>200||dimensions<1||dimensions>4096)
            throw new BizException(ErrorCode.PARAM_ERROR,"Embedding 模型和维度无效（1..4096）");
        var p=providers.requireEnabled(providerId);
        if(p.type()!=ProviderType.OPENAI&&p.type()!=ProviderType.OPENAI_COMPATIBLE)
            throw new BizException(ErrorCode.PARAM_ERROR,"Embedding 需要 OpenAI-compatible Provider");
        return new EmbeddingProfile(p.id(),p.type(),urls.validate(p.baseUrl()),p.authConfig(),model.trim(),dimensions);
    }
    public List<float[]> embed(EmbeddingProfile p,List<String> input,ExecutionControl control) {
        control.throwIfCancelled();
        providers.requireEnabled(p.providerId()); // operational disable remains effective for frozen profiles
        if(input.isEmpty()||input.size()>32)throw invalid();
        input.forEach(v->{TextInput.requireNoNul(v);if(v==null||v.isBlank()||v.length()>16000)throw invalid();});
        String endpoint=urls.validate(p.baseUrl());
        var config=auth.decode(p.authConfig());
        String secret=credentials.resolve(config.credentialRef(),endpoint);
        Map<String,String> headers=config.credentialRef()==null?Map.of():Map.of(config.headerName(),config.prefix()+secret);
        try {
            String body=json.writeValueAsString(Map.of("model",p.model(),"input",input,"encoding_format","float"));
            String response=breaker.execute(p.providerId(),control,()->http.post(endpoint+"/embeddings",headers,body,control));
            if(response.length()>8_000_000)throw invalid();
            var data=json.readTree(response).path("data");
            if(!data.isArray()||data.size()!=input.size())throw invalid();
            float[][] vectors=new float[input.size()][];
            for(var item:data){
                var index=item.path("index");var values=item.path("embedding");
                if(!index.isIntegralNumber()||!index.canConvertToInt())throw invalid();
                int i=index.intValue();
                if(i<0||i>=vectors.length||vectors[i]!=null||!values.isArray()||values.size()!=p.dimensions())throw invalid();
                float[] vector=new float[p.dimensions()];double norm=0;
                for(int j=0;j<vector.length;j++){
                    if(!values.get(j).isNumber())throw invalid();
                    vector[j]=values.get(j).floatValue();if(!Float.isFinite(vector[j]))throw invalid();norm+=(double)vector[j]*vector[j];
                }
                if(norm==0)throw invalid();vectors[i]=vector;
            }
            control.throwIfCancelled();return List.of(vectors);
        } catch(ExecutionCancelledException|ExecutionSuspendedException|LlmApiException e){throw e;}
          catch(Exception e){throw invalid();}
    }
    private BizException invalid(){return new BizException(ErrorCode.CONFLICT,"Embedding 响应无效或向量维度不匹配");}
}
