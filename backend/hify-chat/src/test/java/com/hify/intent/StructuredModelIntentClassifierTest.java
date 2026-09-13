package com.hify.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.runtime.ModelClient;
import com.hify.runtime.RuntimeMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredModelIntentClassifierTest {
    private final StructuredModelIntentClassifier classifier =
            new StructuredModelIntentClassifier(new ObjectMapper());

    @Test
    void parsesJsonCandidateAndPreservesSlots() {
        ModelClient model = request -> RuntimeMessage.assistant("""
                {"candidates":[{"intent":"run_workflow","confidence":0.91,"route":"workflow",
                "slots":{"workflowId":"weekly-report"},"missingSlots":[],"reason":"explicit workflow"}]}
                """);

        var candidates = classifier.classify("执行周报流程", model, "mock");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).route()).isEqualTo(IntentRoute.WORKFLOW);
        assertThat(candidates.get(0).slots()).containsEntry("workflowId", "weekly-report");
    }

    @Test
    void acceptsJsonFenceButRejectsInvalidCandidates() {
        ModelClient model = request -> RuntimeMessage.assistant("""
                ```json
                {"candidates":[
                  {"intent":"calculate","confidence":2,"route":"tool","slots":{},"missingSlots":[]},
                  {"intent":"unknown","confidence":0.5,"route":"unknown","slots":{},"missingSlots":[]}
                ]}
                ```
                """);

        var candidates = classifier.classify("不确定", model, "mock");

        assertThat(candidates).extracting(IntentCandidate::intent).containsExactly("unknown");
    }

    @Test
    void malformedModelOutputFailsClosed() {
        ModelClient model = request -> RuntimeMessage.assistant("我认为这是一个工作流请求");

        assertThat(classifier.classify("执行它", model, "mock")).isEmpty();
    }
}
