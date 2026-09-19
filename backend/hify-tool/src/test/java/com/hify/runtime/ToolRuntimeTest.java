package com.hify.runtime;

import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRuntimeTest {
    private final ToolRuntime tools = new ToolRuntime();

    @Test
    void validatesRequiredCalculatorInput() {
        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "calculator", Map.of()),
                Set.of("calculator"));

        assertThat(result.error()).isTrue();
        assertThat(result.fatal()).isFalse();
        assertThat(result.value()).isEqualTo("Missing required argument: expression");
    }

    @Test
    void deniesToolsOutsideAgentAllowList() {
        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "current_time", Map.of()),
                Set.of());

        assertThat(result.permissionDenied()).isTrue();
        assertThat(result.fatal()).isTrue();
    }

    @Test
    void distinguishesUnavailableToolAndKnownReadOnlyAlternative() {
        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "math", Map.of("expression", "1+1")),
                Set.of("calculator"));

        assertThat(result.failureType()).isEqualTo(ToolRuntime.FailureType.TOOL_UNAVAILABLE);
        assertThat(result.fatal()).isFalse();
        assertThat(tools.findReadOnlyAlternative("math", Set.of("calculator")))
                .contains("calculator");
    }

    @Test
    void rejectsWorkBeforeToolExecutionWhenRunIsCancelled() {
        ExecutionControl control = ExecutionControl.withTimeout(Duration.ofSeconds(1), () -> true);

        assertThatThrownBy(() -> tools.execute(
                new RuntimeMessage.ToolCall("call-1", "calculator", Map.of("expression", "1+1")),
                Set.of("calculator"), control))
                .isInstanceOf(ExecutionCancelledException.class);
    }

    @Test
    void capabilitySnapshotIsDeterministicAndOwnerRevisionIsPinned() {
        CapabilitySnapshot first = tools.snapshot("agent-v1", Set.of("calculator", "current_time"));
        CapabilitySnapshot reordered = tools.snapshot("agent-v1", Set.of("current_time", "calculator"));
        CapabilitySnapshot nextVersion = tools.snapshot("agent-v2", Set.of("calculator", "current_time"));

        assertThat(first.toolSchemaDigest()).isEqualTo(reordered.toolSchemaDigest());
        assertThat(first.revision()).isEqualTo(reordered.revision());
        assertThat(nextVersion.toolSchemaDigest()).isEqualTo(first.toolSchemaDigest());
        assertThat(nextVersion.revision()).isNotEqualTo(first.revision());
    }

    @Test
    void revalidatesLeaseAfterPermissionAndBeforeExecution() {
        CapabilitySnapshot snapshot = tools.snapshot("agent-v1", Set.of("calculator"));
        AtomicInteger validations = new AtomicInteger();
        ToolExecutionLease lease = new ToolExecutionLease("run-1", "attempt-1", snapshot.revision(),
                () -> validations.incrementAndGet() == 1);

        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "calculator", Map.of("expression", "1+1")),
                snapshot, lease, ExecutionControl.none());

        assertThat(validations).hasValue(2);
        assertThat(result.failureType()).isEqualTo(ToolRuntime.FailureType.STALE_LEASE);
        assertThat(result.permissionDenied()).isTrue();
    }
}
