package com.hify.workflow.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.*;
import com.hify.provider.api.TextGenerationPort;
import com.hify.workflow.api.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.*;

@Component
public class WorkflowExternalNodes {
    private final TextGenerationPort models;
    private final WorkflowHttpClient http;
    private final ObjectMapper json;
    private final Duration llmTimeout;
    @Autowired
    public WorkflowExternalNodes(TextGenerationPort models, WorkflowHttpClient http, ObjectMapper json) {
        this(models, http, json, Duration.ofSeconds(45));
    }
    WorkflowExternalNodes(TextGenerationPort models, WorkflowHttpClient http, ObjectMapper json, Duration llmTimeout) {
        this.models = models; this.http = http; this.json = json;
        this.llmTimeout = llmTimeout;
    }
    static boolean isExternal(WorkflowNodeSpec node) {
        return Set.of("LLM", "API_CALL").contains(node.type().toUpperCase(Locale.ROOT));
    }
    static void rejectDraftSnapshots(WorkflowDraftRequest draft) {
        for (var node : draft.nodes()) if (node.config().has("modelSnapshot"))
            throw new WorkflowDefinitionException("modelSnapshot 只能由服务端发布时生成");
    }
    WorkflowDraftRequest freeze(WorkflowDraftRequest draft) {
        rejectDraftSnapshots(draft);
        List<WorkflowNodeSpec> nodes = new ArrayList<>();
        for (var node : draft.nodes()) {
            ObjectNode config = node.config().deepCopy();
            if (node.type().equalsIgnoreCase("LLM")) config.set("modelSnapshot", json.valueToTree(
                    models.freeze(config.path("providerId").asText(), config.path("modelId").asText())));
            if (node.type().equalsIgnoreCase("API_CALL")) http.requireAllowed(config.path("endpoint").asText(), config.path("credentialRef").asText());
            nodes.add(new WorkflowNodeSpec(node.nodeKey(), node.type(), node.name(), config));
        }
        return new WorkflowDraftRequest(draft.name(), draft.description(), draft.schemaVersion(), nodes, draft.edges());
    }
    void requirePublished(WorkflowDraftRequest draft) {
        for (var node : draft.nodes()) {
            if (node.type().equalsIgnoreCase("LLM")) profile(node);
            if (node.type().equalsIgnoreCase("API_CALL")) {
                try { http.requireAllowed(node.config().path("endpoint").asText(), node.config().path("credentialRef").asText()); }
                catch (BizException failure) { throw new WorkflowDefinitionException("HTTP 节点端点或凭据授权已失效，请联系管理员"); }
            }
        }
    }
    String execute(WorkflowNodeSpec node, WorkflowExecutionContext context, ExecutionControl control) {
        WorkflowControl.check(control);
        JsonNode config = node.config();
        if (node.type().equalsIgnoreCase("LLM")) {
            // The node budget is capped by the original Run deadline; retries cannot reset it.
            Duration remaining = control.remaining(llmTimeout);
            WorkflowControl.check(control);
            ExecutionControl bounded = ExecutionControl.withTimeout(remaining, control::isCancelled)
                    .withShutdown(control::isSuspended);
            try {
                String result = models.generate(profile(node), context.resolve(config.path("systemPrompt").asText("")),
                        context.resolve(config.path("prompt").asText()), config.path("temperature").asDouble(0.2),
                        config.path("maxOutputTokens").asInt(1024), bounded);
                checkModelBudget(control, bounded);
                return result;
            } catch (RuntimeException failure) {
                checkModelBudget(control, bounded);
                throw failure;
            }
        }
        Map<String, String> query = new LinkedHashMap<>();
        config.path("query").fields().forEachRemaining(entry -> query.put(entry.getKey(), context.resolve(entry.getValue().asText())));
        return http.get(config.path("endpoint").asText(), config.path("credentialRef").asText(), query, control);
    }
    private static void checkModelBudget(ExecutionControl parent, ExecutionControl node) {
        WorkflowControl.check(parent);
        if (node.isExpired()) throw new BizException(ErrorCode.CONFLICT, "LLM 节点调用超时，工作流已停止");
    }
    private TextGenerationPort.Profile profile(WorkflowNodeSpec node) {
        try {
            var profile = json.treeToValue(node.config().get("modelSnapshot"), TextGenerationPort.Profile.class);
            if (profile == null || profile.provider() == null || !profile.provider().enabled()
                    || !Objects.equals(profile.provider().id(), node.config().path("providerId").asText())
                    || !Objects.equals(profile.modelId(), node.config().path("modelId").asText())) throw new IllegalArgumentException();
            return profile;
        } catch (Exception failure) { throw new WorkflowDefinitionException("LLM 节点缺少有效发布模型快照，请重新发布 Workflow 和 Agent"); }
    }
    static List<String> validate(WorkflowNodeSpec node) {
        JsonNode c = node.config();
        TextInput.requireNoNulInJson(c);
        Set<String> allowed = node.type().equalsIgnoreCase("LLM")
                ? Set.of("providerId", "modelId", "prompt", "systemPrompt", "temperature", "maxOutputTokens", "modelSnapshot", "outputVariable", "__ui")
                : Set.of("endpoint", "method", "credentialRef", "query", "outputVariable", "__ui");
        c.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) fail("节点包含不支持的配置字段"); });
        List<String> templates = new ArrayList<>();
        if (node.type().equalsIgnoreCase("LLM")) {
            fixed(c, "providerId", true); fixed(c, "modelId", true);
            String prompt = text(c, "prompt", true);
            String system = text(c, "systemPrompt", false);
            if (prompt.length() + system.length() > 16000) fail("LLM 模板超过输入预算");
            JsonNode temperature = c.get("temperature"), tokens = c.get("maxOutputTokens");
            if (temperature != null && (!temperature.isNumber() || !Double.isFinite(temperature.asDouble())
                    || temperature.asDouble() < 0 || temperature.asDouble() > 2)) fail("temperature 必须在 0..2");
            if (tokens != null && (!tokens.isIntegralNumber() || !tokens.canConvertToInt() || tokens.asInt() < 1 || tokens.asInt() > 4096))
                fail("maxOutputTokens 必须在 1..4096");
            templates.add(prompt); templates.add(system);
        } else {
            WorkflowHttpClient.canonical(fixed(c, "endpoint", true));
            if (!"GET".equals(c.path("method").asText("GET"))) fail("API_CALL 只允许 GET");
            fixed(c, "credentialRef", false);
            JsonNode query = c.get("query");
            if (query != null) {
                if (!query.isObject() || query.size() > 16) fail("HTTP query 必须是至多16项的对象");
                query.fields().forEachRemaining(entry -> {
                    if (entry.getKey().isBlank() || entry.getKey().length() > 128 || !entry.getValue().isTextual()
                            || entry.getValue().asText().length() > 2048) fail("HTTP query 参数无效或超限");
                    templates.add(entry.getValue().asText());
                });
            }
        }
        return templates;
    }
    private static String fixed(JsonNode c, String field, boolean required) {
        String value = text(c, field, required);
        if (!value.equals(value.trim())) fail("端点、凭据和模型标识不能包含首尾空白");
        if (value.contains("{{") || value.contains("}}")) fail("端点、凭据和模型标识不允许插值");
        return value;
    }
    private static String text(JsonNode c, String field, boolean required) {
        JsonNode value = c.get(field);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual() || (required && value.asText().isBlank())) fail("节点缺少有效文本配置: " + field);
        return value.asText();
    }
    private static void fail(String message) { throw new WorkflowDefinitionException(message); }
}
