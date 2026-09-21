package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.api.WorkflowEdgeSpec;
import com.hify.workflow.api.WorkflowNodeSpec;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WorkflowGraphValidator {
    private static final Set<String> TYPES=Set.of("START","TEMPLATE","CONDITION","KNOWLEDGE","END");
    public void validate(WorkflowDraftRequest draft){
        Map<String,WorkflowNodeSpec> nodes=new LinkedHashMap<>();
        for(var node:draft.nodes()){
            String key=node.nodeKey().trim(); if(nodes.put(key,node)!=null)fail("节点 key 重复: "+key);
            if(!TYPES.contains(node.type().toUpperCase(Locale.ROOT)))fail("不支持的节点类型: "+node.type());
        }
        List<WorkflowNodeSpec> starts=nodes.values().stream().filter(n->n.type().equalsIgnoreCase("START")).toList();
        List<WorkflowNodeSpec> ends=nodes.values().stream().filter(n->n.type().equalsIgnoreCase("END")).toList();
        if(starts.size()!=1)fail("工作流必须恰有一个 START"); if(ends.isEmpty())fail("工作流至少有一个 END");
        Map<String,List<WorkflowEdgeSpec>> outgoing=new HashMap<>(); Set<String> edgeKeys=new HashSet<>();
        for(var edge:edges(draft)){
            if(!edgeKeys.add(edge.edgeKey()))fail("边 key 重复: "+edge.edgeKey());
            if(!nodes.containsKey(edge.sourceNodeKey())||!nodes.containsKey(edge.targetNodeKey()))fail("边引用了不存在的节点: "+edge.edgeKey());
            outgoing.computeIfAbsent(edge.sourceNodeKey(),k->new ArrayList<>()).add(edge);
        }
        for(var node:nodes.values())if(node.type().equalsIgnoreCase("CONDITION")){
            var branches=outgoing.getOrDefault(node.nodeKey(),List.of());
            if(branches.stream().noneMatch(WorkflowEdgeSpec::defaultBranch))fail("CONDITION 缺少默认分支: "+node.nodeKey());
            if(branches.stream().filter(WorkflowEdgeSpec::defaultBranch).count()!=1)fail("CONDITION 必须恰有一个默认分支: "+node.nodeKey());
        }
        Set<String> reached=new HashSet<>(); visit(starts.get(0).nodeKey(),outgoing,reached,new HashSet<>());
        if(reached.size()!=nodes.size()){Set<String> missing=new TreeSet<>(nodes.keySet());missing.removeAll(reached);fail("存在不可达节点: "+missing);}
    }
    private void visit(String key,Map<String,List<WorkflowEdgeSpec>> out,Set<String> reached,Set<String> stack){
        if(!stack.add(key))fail("工作流不能包含循环: "+key); if(!reached.add(key)){stack.remove(key);return;}
        for(var edge:out.getOrDefault(key,List.of()))visit(edge.targetNodeKey(),out,reached,stack); stack.remove(key);
    }
    private List<WorkflowEdgeSpec> edges(WorkflowDraftRequest d){return d.edges()==null?List.of():d.edges();}
    private void fail(String message){throw new BizException(ErrorCode.PARAM_ERROR,message);}
}
