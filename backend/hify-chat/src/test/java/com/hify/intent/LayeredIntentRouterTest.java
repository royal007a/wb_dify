package com.hify.intent;

import com.hify.runtime.ModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredIntentRouterTest {
    private static final ModelClient UNUSED_MODEL = request -> {
        throw new AssertionError("Model client should be delegated through the classifier stub");
    };

    @Test
    void deterministicLayerShortCircuitsTheModelLayer() {
        AtomicBoolean called = new AtomicBoolean();
        LayeredIntentRouter router = router((input, client, model) -> {
            called.set(true);
            return List.of();
        });

        IntentDecision decision = router.route("现在几点？", UNUSED_MODEL, "mock");

        assertThat(decision.intent()).isEqualTo("current_time");
        assertThat(called).isFalse();
    }

    @Test
    void acceptsHighConfidenceStructuredCandidate() {
        LayeredIntentRouter router = router((input, client, model) -> List.of(candidate(
                "run_workflow", 0.91, IntentRoute.WORKFLOW,
                Map.of("workflowId", "weekly-report"), List.of())));

        IntentDecision decision = router.route("执行每周管理简报流程", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.WORKFLOW);
        assertThat(decision.slots()).containsEntry("workflowId", "weekly-report");
    }

    @Test
    void lowConfidenceCandidateBecomesClarification() {
        LayeredIntentRouter router = router((input, client, model) -> List.of(candidate(
                "run_workflow", 0.62, IntentRoute.WORKFLOW,
                Map.of("workflowId", "weekly-report"), List.of())));

        IntentDecision decision = router.route("处理一下周报", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.reason()).isEqualTo("confidence_below_threshold");
    }

    @Test
    void closeCandidatesBecomeClarification() {
        LayeredIntentRouter router = router((input, client, model) -> List.of(
                candidate("calculate", 0.84, IntentRoute.TOOL,
                        Map.of("toolName", "calculator", "expression", "1+1"), List.of()),
                candidate("run_workflow", 0.79, IntentRoute.WORKFLOW,
                        Map.of("workflowId", "calculator-flow"), List.of())));

        IntentDecision decision = router.route("把这批数字处理一下", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.reason()).isEqualTo("ambiguous_top_candidates");
        assertThat(decision.evidence()).anyMatch(value -> value.reference().equals("router.ambiguity_margin"));
    }

    @Test
    void missingSlotsOverrideExecutableModelRoute() {
        LayeredIntentRouter router = router((input, client, model) -> List.of(candidate(
                "run_workflow", 0.93, IntentRoute.WORKFLOW, Map.of(), List.of("workflowId"))));

        IntentDecision decision = router.route("运行那个流程", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.missingSlots()).containsExactly("workflowId");
    }

    @Test
    void emptyModelResultBecomesUnknown() {
        IntentDecision decision = router((input, client, model) -> List.of())
                .route("今天天气适合出门吗", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.UNKNOWN);
        assertThat(decision.intent()).isEqualTo("unknown");
    }

    @Test
    void modelFailureFailsClosedWithoutLeakingItsMessage() {
        IntentDecision decision = router((input, client, model) -> {
            throw new IllegalStateException("secret provider response");
        }).route("帮我处理业务请求", UNUSED_MODEL, "mock");

        assertThat(decision.route()).isEqualTo(IntentRoute.UNKNOWN);
        assertThat(decision.reason()).isEqualTo("model_classification_failed");
        assertThat(decision.evidence()).extracting(IntentEvidence::detail)
                .containsExactly("IllegalStateException")
                .noneMatch(value -> value.contains("secret"));
    }

    @Test
    void modelCannotInventIntentOrExecutableTarget() {
        LayeredIntentRouter inventedIntent = router((input, client, model) -> List.of(candidate(
                "delete_database", 0.99, IntentRoute.TOOL,
                Map.of("toolName", "shell"), List.of())));
        LayeredIntentRouter wrongTarget = router((input, client, model) -> List.of(candidate(
                "calculate", 0.99, IntentRoute.TOOL,
                Map.of("toolName", "shell", "expression", "1+1"), List.of())));

        assertThat(inventedIntent.route("处理请求", UNUSED_MODEL, "mock").route())
                .isEqualTo(IntentRoute.UNKNOWN);
        assertThat(wrongTarget.route("处理另一个请求", UNUSED_MODEL, "mock").route())
                .isEqualTo(IntentRoute.UNKNOWN);
    }

    private LayeredIntentRouter router(ModelIntentClassifier classifier) {
        return new LayeredIntentRouter(new DeterministicIntentRouter(), classifier, 0.75, 0.10);
    }

    private IntentCandidate candidate(String intent, double confidence, IntentRoute route,
                                      Map<String, Object> slots, List<String> missingSlots) {
        return new IntentCandidate(intent, confidence, route, slots, missingSlots,
                List.of(new IntentEvidence("MODEL", "test", "fixture")), "test_fixture");
    }
}
