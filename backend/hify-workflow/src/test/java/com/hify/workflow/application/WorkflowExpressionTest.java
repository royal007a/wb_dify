package com.hify.workflow.application;

import com.hify.common.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.hify.workflow.application.WorkflowFixtures.*;

class WorkflowExpressionTest {
    @Test void allOperatorsUseDecodedQuotedLiteralsNotInjectedSyntax(){
        var ctx=new WorkflowExecutionContext("start","a contains b == c != d");
        assertThat(WorkflowExpression.parse("{{start.userMessage}} == 'a contains b == c != d'").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("{{start.userMessage}} contains \"b == c\"").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("{{start.userMessage}} != 'other'").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("'contains' == 'contains'").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("'a' contains ''").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("\"a\\\"b\" == 'a\"b'").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("'a\\'b' == \"a'b\"").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("\"a\\n b\" == \"a\\n b\"").evaluate(ctx)).isTrue();
        assertThat(WorkflowExpression.parse("'a'\tcontains\t'a'").evaluate(ctx)).isTrue();
    }
    @Test void dynamicBooleanIsDataAndMustBeBoolean(){
        assertThat(WorkflowExpression.parse("{{start.userMessage}}").evaluate(new WorkflowExecutionContext("start","TRUE"))).isTrue();
        assertThatThrownBy(()->WorkflowExpression.parse("{{start.userMessage}}").evaluate(new WorkflowExecutionContext("start","true == true")))
                .isInstanceOf(BizException.class);
    }
    @Test void rejectsUnbalancedQuotesMultipleOperatorsAndTrailingSyntax(){
        for(String value:List.of("'a' == 'a' junk","'a' == 'a' != 'b'","'a' contains 'b' contains 'c'","'a' && 'b'","== 'a'","a ==","a == \"\\q\"","a == 'b' 'c'"))
            assertThatThrownBy(()->WorkflowExpression.parse(value)).as(value).isInstanceOf(BizException.class);
    }
    @Test void encodedTemplateReferencesCannotBypassDominators(){
        String encoded="'\\u007b\\u007bmissing.result\\u007d\\u007d' == 'x'";
        assertThatThrownBy(()->new WorkflowGraphValidator().validate(condition(encoded))).isInstanceOf(BizException.class);
        String valid="'\\u007b\\u007bstart.userMessage\\u007d\\u007d' == 'x'";
        new WorkflowGraphValidator().validate(condition(valid));
        assertThat(WorkflowExpression.parse(valid).evaluate(new WorkflowExecutionContext("start","x"))).isTrue();
    }
}
