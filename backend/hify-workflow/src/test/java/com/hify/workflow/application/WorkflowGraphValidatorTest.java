package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.workflow.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.hify.workflow.application.WorkflowFixtures.*;

class WorkflowGraphValidatorTest {
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();

    @Test void rejectsReachableNonEndDeadEnd() {
        rejects(draft(List.of(node("start","START"), node("route","CONDITION","expression","true"),
                node("dead","TEMPLATE","template","lost"), node("end","END","output","ok")),
                edge("start","route"), branch("dead","true",false), branch("end",null,true)));
    }
    @Test void rejectsMultipleNonConditionalSuccessors() {
        rejects(draft(List.of(node("start","START"),node("one","END","output","1"),node("two","END","output","2")),
                edge("start","one"),edge("start","two")));
    }
    @Test void rejectsEndOutgoingEdge() {
        rejects(draft(List.of(node("start","START"),node("end","END","output","1"),node("ignored","END","output","2")),
                edge("start","end"),edge("end","ignored")));
    }
    @Test void rejectsBranchLocalValueAtMerge() { rejects(diamond("{{left.result}}")); }
    @Test void acceptsStrictDominatorAtMerge() { validator.validate(diamond("{{start.userMessage}} / {{route.result}}")); }
    @Test void acceptsRepeatedPredecessorEdges() {
        validator.validate(draft(List.of(node("start","START"), node("route","CONDITION","expression","true"),
                node("end","END","output","{{route.result}}")),edge("start","route"),
                new WorkflowEdgeSpec("yes","route","end","true",false),
                new WorkflowEdgeSpec("fallback","route","end",null,true)));
    }
    @Test void rejectsUnknownVariableOnKnownDominator() { rejects(diamond("{{start.password}}")); }
    @Test void rejectsUnknownNode() { rejects(diamond("{{missing.result}}")); }
    @Test void rejectsSelfReference() { rejects(diamond("{{end.result}}")); }
    @Test void rejectsMalformedPlaceholder() { rejects(diamond("{{start.userMessage}")); }
    @Test void acceptsCustomOutputNameAndSpacedPlaceholder() {
        validator.validate(draft(List.of(node("entry","START"),node("format","TEMPLATE","template","{{ entry.userMessage }}","outputVariable","answer"),
                node("end","END","output","{{format.answer}}")),edge("entry","format"),edge("format","end")));
    }
    @Test void rejectsUndeclaredDefaultOutputWhenCustomVariableConfigured() {
        rejects(draft(List.of(node("start","START"),node("format","TEMPLATE","template","x","outputVariable","answer"),
                node("end","END","output","{{format.result}}")),edge("start","format"),edge("format","end")));
    }
    @Test void rejectsMissingTemplateConfiguration() {
        rejects(draft(List.of(node("start","START"),node("format","TEMPLATE"),node("end","END","output","x")),
                edge("start","format"),edge("format","end")));
    }
    @Test void rejectsNonBooleanBranchAndDuplicatePredicate() {
        var good = diamond("ok");
        for (String condition : List.of("refund", "TRUE")) {
            var edges = new ArrayList<>(good.edges());
            edges.add(new WorkflowEdgeSpec("duplicate","route","right",condition,false));
            rejects(new WorkflowDraftRequest("invalid","",1,good.nodes(),edges));
        }
    }
    @Test void rejectsConditionOnOrdinaryEdge() {
        rejects(draft(List.of(node("start","START"),node("end","END","output","x")),
                new WorkflowEdgeSpec("edge","start","end","true",false)));
    }
    @Test void rejectsKeysWhoseWhitespaceWouldChangeAtPersistence() {
        rejects(draft(List.of(node(" start ","START"),node("end","END","output","x")),edge(" start ","end")));
    }
    @Test void invalidStoredNullGraphFailsWithBusinessErrorNotNullPointer() {
        rejects(new WorkflowDraftRequest("invalid","",1,null,null));
    }
    private void rejects(WorkflowDraftRequest draft) {
        assertThatThrownBy(() -> validator.validate(draft)).isInstanceOf(BizException.class);
    }
}
