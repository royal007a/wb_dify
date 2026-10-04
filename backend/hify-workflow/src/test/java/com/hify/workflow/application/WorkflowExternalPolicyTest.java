package com.hify.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.*;
import com.hify.provider.api.*;
import com.hify.workflow.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowExternalPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode llm() { return json.createObjectNode().put("providerId", "p").put("modelId", "m").put("prompt", "{{start.userMessage}}"); }
    private ObjectNode http() { return json.createObjectNode().put("endpoint", "http://127.0.0.1/read").put("method", "GET"); }
    private WorkflowNodeSpec node(String type, ObjectNode config) { return new WorkflowNodeSpec("work", type, "test", config); }

    @Test void llmConfigurationBoundsRejectInvalidTypesAndUnknownFields() {
        for (int tokens : List.of(1, 4096)) for (double temperature : List.of(0d, 2d))
            assertThatCode(() -> WorkflowExternalNodes.validate(node("LLM", llm().put("maxOutputTokens", tokens).put("temperature", temperature))))
                    .doesNotThrowAnyException();
        List<ObjectNode> invalid = List.of(llm().put("maxOutputTokens", 0), llm().put("maxOutputTokens", 4097),
                llm().put("maxOutputTokens", 1.5), llm().put("maxOutputTokens", "123"), llm().put("temperature", -0.01),
                llm().put("temperature", 2.01), llm().put("temperature", "1"), llm().put("temperature", Double.NaN), llm().put("token", "hidden"));
        for (ObjectNode config : invalid)
            assertThatThrownBy(() -> WorkflowExternalNodes.validate(node("LLM", config))).isInstanceOf(WorkflowDefinitionException.class);
    }
    @Test void httpQueryAndMethodBoundsHaveValidBoundaryControls() {
        ObjectNode valid = http(), query = valid.putObject("query");
        for (int i = 0; i < 16; i++) query.put("k" + i, "x".repeat(2048));
        assertThat(WorkflowExternalNodes.validate(node("API_CALL", valid))).hasSize(16);
        query.put("extra", "value");
        assertThatThrownBy(() -> WorkflowExternalNodes.validate(node("API_CALL", valid))).isInstanceOf(WorkflowDefinitionException.class);
        query.remove("extra"); query.put("k0", "x".repeat(2049));
        assertThatThrownBy(() -> WorkflowExternalNodes.validate(node("API_CALL", valid))).isInstanceOf(WorkflowDefinitionException.class);
        for (ObjectNode config : List.of(http().put("method", "get"), http().put("method", "POST"), http().put("headers", "hidden")))
            assertThatThrownBy(() -> WorkflowExternalNodes.validate(node("API_CALL", config))).isInstanceOf(WorkflowDefinitionException.class);
    }
    @Test void revokedCredentialBindingRejectsTheSamePreviouslyPublishableGraph() throws Exception {
        String endpoint = "http://127.0.0.1/read", reference = "env:WORKFLOW_READ_KEY";
        var model = mock(TextGenerationPort.class);
        var config = http().put("credentialRef", reference);
        var draft = new WorkflowDraftRequest("read", "", 1, List.of(node("API_CALL", config)), List.of());
        var allowed = new CredentialReferencePolicy(json.writeValueAsString(Map.of(reference, List.of(endpoint))), json);
        var denied = new CredentialReferencePolicy("{}", json);
        var original = new WorkflowExternalNodes(model, new WorkflowHttpClient(json.writeValueAsString(List.of(endpoint)), json, allowed), json);
        var published = original.freeze(draft);
        assertThatCode(() -> original.requirePublished(published)).doesNotThrowAnyException();
        var revoked = new WorkflowExternalNodes(model, new WorkflowHttpClient(json.writeValueAsString(List.of(endpoint)), json, denied), json);
        assertThatThrownBy(() -> revoked.freeze(draft)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> revoked.requirePublished(published)).isInstanceOf(WorkflowDefinitionException.class).hasMessageContaining("授权已失效");
        verifyNoInteractions(model);
    }
    @Test void nodeModelDeadlineDoesNotImpersonateTheParentRunDeadline() {
        for (boolean throwsFailure : List.of(false, true)) {
            TextGenerationPort model = mock(TextGenerationPort.class);
            when(model.generate(any(), anyString(), anyString(), anyDouble(), anyInt(), any())).thenAnswer(call -> {
                ExecutionControl bounded = call.getArgument(5);
                while (!bounded.isExpired()) LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                if (throwsFailure) throw new IllegalStateException("fixture node timeout");
                return "late";
            });
            var external = new WorkflowExternalNodes(model, mock(WorkflowHttpClient.class), json, Duration.ofMillis(20));
            var config = llm(); config.set("modelSnapshot", json.valueToTree(new TextGenerationPort.Profile(
                    new ProviderRuntimeConfig("p", "fixture", ProviderType.MOCK, "", "", "m", true), "m")));
            var parent = ExecutionControl.withTimeout(Duration.ofSeconds(20), () -> false);
            assertThatThrownBy(() -> external.execute(node("LLM", config), new WorkflowExecutionContext("input"), parent))
                    .isInstanceOf(BizException.class).hasMessage("LLM 节点调用超时，工作流已停止");
            assertThat(parent.isExpired()).isFalse();
            verify(model).generate(any(), anyString(), anyString(), anyDouble(), anyInt(), any());
        }
    }
}
