package com.hify.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MockModelClientTest {
    private final MockModelClient client = new MockModelClient();
    private static final ToolDefinition CLOCK = new ToolDefinition("current_time", "time", Map.of("type", "object"), "read");
    private static final ToolDefinition CALCULATOR = new ToolDefinition("calculator", "math", Map.of("type", "object"), "read");

    @ParameterizedTest
    @ValueSource(strings = {"现在几点", "现在几点？", "现在几点?", "请问现在几点了", "当前时间", "今天几号", "今天日期", "What time is it?", "TIME"})
    void timePromptsProduceStructuredClockCalls(String input) {
        var reply = client.generate(new ModelRequest("hify-mock", 0, List.of(RuntimeMessage.user(input)), List.of(CLOCK, CALCULATOR)));
        assertThat(reply.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("current_time");
            assertThat(call.arguments()).isEmpty();
            assertThat(call.id()).isNotBlank();
        });
    }

    @Test void unavailableClockDoesNotProduceCallOrMisleadingSuggestion() {
        var reply = client.generate(new ModelRequest("hify-mock", 0, List.of(RuntimeMessage.user("现在几点？")), List.of(CALCULATOR)));
        assertThat(reply.toolCalls()).isEmpty();
        assertThat(reply.content()).contains("Demo", "模拟").doesNotContain("你可以问我“现在几点");
    }

    @Test void aNewTurnDoesNotReusePreviousToolResults() {
        var history = List.of(RuntimeMessage.user("计算 2 * 3"), RuntimeMessage.toolResult("old", "6", false), RuntimeMessage.assistant("6"), RuntimeMessage.user("现在几点？"));
        var reply = client.generate(new ModelRequest("hify-mock", 0, history, List.of(CLOCK, CALCULATOR)));
        assertThat(reply.toolCalls()).singleElement().extracting(RuntimeMessage.ToolCall::name).isEqualTo("current_time");
    }

    @Test void toolResultCompletesWithoutCallingAgain() {
        var reply = client.generate(new ModelRequest("hify-mock", 0, List.of(RuntimeMessage.toolResult("clock", "2026-10-03T12:00:00Z", false)), List.of(CLOCK)));
        assertThat(reply.toolCalls()).isEmpty();
        assertThat(reply.content()).contains("2026-10-03T12:00:00Z");
    }

    @Test void unrelatedEnglishWordsDoNotTriggerTimeTool() {
        var reply = client.generate(new ModelRequest("hify-mock", 0, List.of(RuntimeMessage.user("explain timeout in runtime")), List.of(CLOCK)));
        assertThat(reply.toolCalls()).isEmpty();
    }
}
