package com.hify.workflow.application;

import com.hify.common.BizException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowExecutionContextTest {
    @Test void missingVariableFailsClosed() {
        var context = new WorkflowExecutionContext("hi");
        assertThatThrownBy(() -> context.resolve("answer: {{absent.result}}"))
                .isInstanceOf(BizException.class).hasMessageContaining("absent.result");
    }
    @Test void replacementValuesAreDataNotNewTemplateSource() {
        var context = new WorkflowExecutionContext("{{lookup.result}}");
        context.set("lookup", "result", "private-value");
        assertThat(context.resolve("{{start.userMessage}}")).isEqualTo("{{lookup.result}}");
    }
    @Test void resolvesWhitespaceAndRegexCharactersLiterally() {
        var context = new WorkflowExecutionContext("$1\\path");
        assertThat(context.resolve("{{ start.userMessage }}")).isEqualTo("$1\\path");
    }
    @Test void malformedTemplateFailsBeforeRendering() {
        var context = new WorkflowExecutionContext("hi");
        assertThatThrownBy(() -> context.resolve("{{start.userMessage}")).isInstanceOf(BizException.class);
    }
    @Test void valuesRemainWriteOnce() {
        var context = new WorkflowExecutionContext("hi");
        context.set("a", "result", "first");
        assertThatThrownBy(() -> context.set("a", "result", "second")).isInstanceOf(BizException.class);
        assertThat(context.get("a.result")).isEqualTo("first");
    }
}
