package com.hify.provider.application;

import com.hify.common.*;
import com.hify.provider.api.*;
import com.hify.runtime.*;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ProviderTextGenerationService implements TextGenerationPort {
    private final ProviderQueryService providers;
    private final ModelClientFactory clients;
    public ProviderTextGenerationService(ProviderQueryService providers, ModelClientFactory clients) {
        this.providers = providers; this.clients = clients;
    }
    @Override public Profile freeze(String providerId, String modelId) {
        TextInput.requireNoNul(providerId, modelId);
        var provider = providers.requireEnabled(providerId);
        return new Profile(provider, providers.requireEnabledModel(providerId, modelId));
    }
    @Override public String generate(Profile profile, String system, String prompt, double temperature,
                                    int maxOutputTokens, ExecutionControl control) {
        control.throwIfCancelled();
        TextInput.requireNoNul(system, prompt);
        if (profile == null || profile.provider() == null || profile.modelId() == null
                || system == null || prompt == null || prompt.isBlank() || system.length() + prompt.length() > 16000
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2)
            throw new BizException(ErrorCode.PARAM_ERROR, "LLM 节点配置或输入预算无效");
        // Operational disable applies now; endpoint, model and credential reference remain frozen.
        providers.requireEnabled(profile.provider().id());
        providers.requireEnabledModel(profile.provider().id(), profile.modelId());
        try {
            var result = clients.create(profile.provider()).generate(new ModelRequest(profile.modelId(), temperature,
                    List.of(RuntimeMessage.system(system), RuntimeMessage.user(prompt)), List.of(), control, maxOutputTokens));
            control.throwIfCancelled();
            if (result == null || (result.toolCalls() != null && !result.toolCalls().isEmpty())
                    || result.content() == null || result.content().isBlank()
                    || result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
                throw new BizException(ErrorCode.CONFLICT, "LLM 节点未返回有效的有界纯文本");
            TextInput.requireNoNul(result.content());
            return result.content();
        } catch (ExecutionCancelledException | ExecutionSuspendedException failure) { throw failure; }
        catch (RuntimeException failure) {
            control.throwIfCancelled();
            // Never persist provider errors, response bodies, prompts or credential metadata as an error.
            throw new BizException(ErrorCode.CONFLICT, "LLM 节点调用失败或输出超限");
        }
    }
}
