package com.hify.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.workflow.api.*;
import java.util.*;

final class WorkflowFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static WorkflowNodeSpec node(String key, String type, String... fields) {
        var config = JSON.createObjectNode();
        for (int i = 0; i < fields.length; i += 2) config.put(fields[i], fields[i + 1]);
        return new WorkflowNodeSpec(key, type, key, config);
    }
    static WorkflowEdgeSpec edge(String from, String to) {
        return new WorkflowEdgeSpec(from + "-" + to, from, to, null, false);
    }
    static WorkflowEdgeSpec branch(String to, String condition, boolean fallback) {
        return new WorkflowEdgeSpec("route-" + to, "route", to, condition, fallback);
    }
    static WorkflowDraftRequest draft(List<WorkflowNodeSpec> nodes, WorkflowEdgeSpec... edges) {
        return new WorkflowDraftRequest("graph-test", "", 1, nodes, List.of(edges));
    }
    static WorkflowDraftRequest diamond(String output) {
        return draft(List.of(node("start", "START"), node("route", "CONDITION", "expression", "true"),
                node("left", "TEMPLATE", "template", "left"), node("right", "TEMPLATE", "template", "right"),
                node("end", "END", "output", output)),
                edge("start", "route"), branch("left", "true", false), branch("right", null, true),
                edge("left", "end"), edge("right", "end"));
    }
    static WorkflowDraftRequest chain(int count) {
        var nodes=new ArrayList<WorkflowNodeSpec>();var edges=new ArrayList<WorkflowEdgeSpec>();
        nodes.add(node("start","START"));String previous="start";
        for(int i=1;i<count-1;i++){String key="n"+i;nodes.add(node(key,"TEMPLATE","template","x"));edges.add(edge(previous,key));previous=key;}
        nodes.add(node("end","END","output","done"));edges.add(edge(previous,"end"));
        return draft(nodes,edges.toArray(WorkflowEdgeSpec[]::new));
    }
    static WorkflowDraftRequest condition(String expression) {
        return draft(List.of(node("start","START"),node("route","CONDITION","expression",expression),
                node("end","END","output","{{route.result}}")),edge("start","route"),branch("end",null,true));
    }
}
