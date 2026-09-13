package com.hify.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicIntentRouterTest {
    private final DeterministicIntentRouter router = new DeterministicIntentRouter();

    @Test
    void normalizesWidthAndWhitespaceBeforeMatching() {
        IntentDecision decision = router.route("  ／ｈｅｌｐ　 ").orElseThrow();

        assertThat(decision.intent()).isEqualTo("show_help");
        assertThat(decision.normalizedInput()).isEqualTo("/help");
    }

    @Test
    void extractsCalculatorExpressionAsARequiredSlot() {
        IntentDecision decision = router.route("请帮我计算 12.5 * 4").orElseThrow();

        assertThat(decision.route()).isEqualTo(IntentRoute.TOOL);
        assertThat(decision.slots()).containsEntry("toolName", "calculator")
                .containsEntry("expression", "12.5*4");
    }

    @Test
    void clarifiesWhenCalculatorExpressionIsMissing() {
        IntentDecision decision = router.route("帮我算一下").orElseThrow();

        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.missingSlots()).containsExactly("expression");
    }

    @Test
    void exactWorkflowCommandRequiresWorkflowId() {
        IntentDecision missing = router.route("/workflow").orElseThrow();
        IntentDecision complete = router.route("/workflow daily-report").orElseThrow();

        assertThat(missing.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(missing.missingSlots()).containsExactly("workflowId");
        assertThat(complete.route()).isEqualTo(IntentRoute.WORKFLOW);
        assertThat(complete.slots()).containsEntry("workflowId", "daily-report");
    }

    @Test
    void dangerousActionNeverGoesDirectlyToExecution() {
        IntentDecision decision = router.route("删除所有工作流数据").orElseThrow();

        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.missingSlots()).contains("confirmation");
        assertThat(decision.reason()).isEqualTo("dangerous_action_requires_confirmation");
    }

    @Test
    void leavesBusinessLanguageForTheModelLayer() {
        assertThat(router.route("把周报整理成给管理层看的摘要")).isEmpty();
    }
}
