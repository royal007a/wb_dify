package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A deterministic acceptance set; production traces can be added without changing metric contracts. */
class ContextManagementEvaluationTest {
    @Test
    void meetsSemanticSafetyThresholdsInAdditionToReducingTokens() {
        List<RuntimeMessage> messages = new ArrayList<>();
        messages.add(RuntimeMessage.system("必须保留租户隔离约束；禁止泄露 API Key"));
        messages.add(RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                "inspect-1", "inspect_logs", Map.of("service", "billing")))));
        messages.add(RuntimeMessage.toolResult("inspect-1", "trace\n" + "x".repeat(1_500), false));
        for (int index = 0; index < 10; index++) {
            messages.add(RuntimeMessage.user("old note " + index + " " + "n".repeat(50)));
        }
        messages.add(RuntimeMessage.user("give the current conclusion"));

        ContextManager.PreparedContext prepared = new ContextManager().prepare(messages, List.of(),
                new ContextBudget(260, 30, 20, 10));
        ContextQualityEvaluator.Metrics metrics = new ContextQualityEvaluator().evaluate(prepared,
                List.of("必须保留租户隔离约束", "禁止泄露 API Key"));

        assertThat(metrics.constraintRetentionRate()).isEqualTo(1d);
        assertThat(metrics.duplicateInvestigationRate()).isLessThanOrEqualTo(0.10d);
        assertThat(metrics.recoverySuccessRate()).isEqualTo(1d);
        assertThat(metrics.tokenReductionRate()).isGreaterThan(0.40d);
    }
}
