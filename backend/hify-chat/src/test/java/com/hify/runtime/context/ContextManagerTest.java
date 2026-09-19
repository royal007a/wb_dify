package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextManagerTest {
    private final ContextManager manager = new ContextManager();

    @Test
    void computesIndependentInputOutputReserveAndSafetyBudget() {
        ContextBudget budget = new ContextBudget(1_000, 200, 100, 50);
        assertThat(budget.inputLimit()).isEqualTo(650);
    }

    @Test
    void archivesLargeToolResultsBeforeCompactionAndKeepsRecoveryPointer() {
        List<RuntimeMessage> messages = List.of(
                RuntimeMessage.user("inspect logs"),
                RuntimeMessage.toolResult("call-42", "x".repeat(2_000), false),
                RuntimeMessage.user("what failed?"));

        ContextManager.PreparedContext prepared = manager.prepare(messages, List.of(),
                new ContextBudget(300, 30, 20, 10));

        assertThat(prepared.archived()).isTrue();
        assertThat(prepared.compacted()).isFalse();
        assertThat(prepared.messages().get(1).content()).contains("history://tool-result/call-42");
        assertThat(prepared.archiveReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.toolCallId()).isEqualTo("call-42");
            assertThat(reference.originalContent()).contains("x".repeat(100));
        });
        assertThat(prepared.preparedTokens()).isLessThan(prepared.originalTokens());
    }

    @Test
    void compactsOnlyAfterArchivingAndRemeasuresTheResult() {
        List<RuntimeMessage> messages = new ArrayList<>();
        messages.add(RuntimeMessage.system("必须保留租户隔离约束"));
        for (int index = 0; index < 12; index++) {
            messages.add(RuntimeMessage.user("investigation chunk " + index + " " + "z".repeat(60)));
        }
        messages.add(RuntimeMessage.user("latest question"));

        ContextManager.PreparedContext prepared = manager.prepare(messages, List.of(),
                new ContextBudget(180, 20, 15, 10));

        assertThat(prepared.compacted()).isTrue();
        assertThat(prepared.preparedTokens()).isLessThanOrEqualTo(135);
        assertThat(prepared.messages().get(0).content()).contains("必须保留租户隔离约束");
        assertThat(prepared.messages().get(prepared.messages().size() - 1).content())
                .isEqualTo("latest question");
    }

    @Test
    void rejectsContextThatStillExceedsBudgetAfterCompaction() {
        CheckpointCompactor ineffective = (messages, target) -> messages;
        ContextManager constrained = new ContextManager(new ContextTokenEstimator(),
                new ToolResultArchiver(), ineffective);

        assertThatThrownBy(() -> constrained.prepare(
                List.of(RuntimeMessage.user("x".repeat(1_000))), List.of(),
                new ContextBudget(50, 5, 4, 3)))
                .isInstanceOf(ContextWindowExceededException.class);
    }
}
