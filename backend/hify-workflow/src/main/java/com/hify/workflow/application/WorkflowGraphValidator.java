package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Same DSL validation for storage, publication and execution. */
@Component
public class WorkflowGraphValidator {
    public static final int MAX_EXECUTION_STEPS = 50;
    private static final Set<String> TYPES = Set.of("START", "TEMPLATE", "CONDITION", "KNOWLEDGE", "END");

    public void validate(WorkflowDraftRequest draft) {
        try {validateGraph(draft);}
        catch(BizException invalid){throw new WorkflowDefinitionException(invalid.getMessage());}
    }
    private void validateGraph(WorkflowDraftRequest draft) {
        if (draft == null || draft.nodes() == null || draft.nodes().isEmpty()) fail("工作流节点不能为空");
        Map<String, WorkflowNodeSpec> nodes = new LinkedHashMap<>();
        for (var node : draft.nodes()) {
            if (node == null || !WorkflowTemplates.identifier(node.nodeKey())) fail("节点 key 格式无效");
            if (nodes.put(node.nodeKey(), node) != null) fail("节点 key 重复: " + node.nodeKey());
            if (!TYPES.contains(type(node))) fail("不支持的节点类型");
            if (node.config() == null || !node.config().isObject()) fail("节点配置必须是对象: " + node.nodeKey());
        }
        var starts = nodes.values().stream().filter(n -> type(n).equals("START")).toList();
        if (starts.size() != 1) fail("工作流必须恰有一个 START");
        if (nodes.values().stream().noneMatch(n -> type(n).equals("END"))) fail("工作流至少有一个 END");
        String start = starts.get(0).nodeKey();
        Map<String, List<WorkflowEdgeSpec>> out = new HashMap<>();
        Map<String, Set<String>> predecessors = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        for (var edge : draft.edges() == null ? List.<WorkflowEdgeSpec>of() : draft.edges()) {
            if (edge == null || !WorkflowTemplates.identifier(edge.edgeKey())) fail("边 key 格式无效");
            if (!edgeKeys.add(edge.edgeKey())) fail("边 key 重复: " + edge.edgeKey());
            if (!nodes.containsKey(edge.sourceNodeKey()) || !nodes.containsKey(edge.targetNodeKey()))
                fail("边引用了不存在的节点: " + edge.edgeKey());
            if (start.equals(edge.targetNodeKey())) fail("START 不允许入边");
            out.computeIfAbsent(edge.sourceNodeKey(), ignored -> new ArrayList<>()).add(edge);
            predecessors.computeIfAbsent(edge.targetNodeKey(), ignored -> new LinkedHashSet<>()).add(edge.sourceNodeKey());
        }
        for (var node : nodes.values()) {
            var successors = out.getOrDefault(node.nodeKey(), List.of());
            switch (type(node)) {
                case "END" -> { if (!successors.isEmpty()) fail("END 不允许出边: " + node.nodeKey()); }
                case "CONDITION" -> validateBranches(node.nodeKey(), successors);
                default -> {
                    if (successors.size() != 1) fail("非条件节点必须恰有一条出边: " + node.nodeKey());
                    if (successors.get(0).condition() != null && !successors.get(0).condition().isBlank())
                        fail("非条件节点不能配置分支条件: " + node.nodeKey());
                }
            }
        }
        // Unique predecessors handle parallel edges. Iterative ordering avoids recursive overflow.
        Map<String, Integer> degrees = new HashMap<>();
        nodes.keySet().forEach(k -> degrees.put(k, predecessors.getOrDefault(k, Set.of()).size()));
        ArrayDeque<String> ready = new ArrayDeque<>();
        degrees.forEach((key, degree) -> { if (degree == 0) ready.add(key); });
        if (ready.size() != 1 || !ready.contains(start)) fail("存在不可达节点");
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String key = ready.remove();
            order.add(key);
            out.getOrDefault(key, List.of()).stream().map(WorkflowEdgeSpec::targetNodeKey).distinct().forEach(target -> {
                if (degrees.compute(target, (ignored, degree) -> degree - 1) == 0) ready.add(target);
            });
        }
        if (order.size() != nodes.size()) fail("工作流包含循环或不可达节点");
        Map<String, Set<String>> dominators = new HashMap<>();
        Map<String, String> outputs = new HashMap<>();
        Map<String, Integer> pathLengths = new HashMap<>();
        for (String key : order) {
            var node = nodes.get(key);
            int length=1+predecessors.getOrDefault(key,Set.of()).stream().mapToInt(pathLengths::get).max().orElse(0);
            if(length>MAX_EXECUTION_STEPS)fail("工作流最长路径超过 "+MAX_EXECUTION_STEPS+" 步（含 START/END）");
            pathLengths.put(key,length);
            Set<String> strict = null;
            for (String predecessor : predecessors.getOrDefault(key, Set.of())) {
                if (strict == null) strict = new HashSet<>(dominators.get(predecessor));
                else strict.retainAll(dominators.get(predecessor));
            }
            if (strict == null) strict = new HashSet<>();
            for (String template : templates(node)) {
                for (String reference : WorkflowTemplates.references(template)) {
                    int separator = reference.indexOf('.');
                    String owner = reference.substring(0, separator), variable = reference.substring(separator + 1);
                    if (!strict.contains(owner)) fail("模板必须引用必经上游节点: " + key + " -> " + reference);
                    if (!variable.equals(outputs.get(owner))) fail("模板引用未声明的变量: " + key + " -> " + reference);
                }
            }
            String output = output(node);
            if (output != null && !WorkflowTemplates.identifier(output)) fail("输出变量名格式无效: " + key);
            outputs.put(key, output);
            strict.add(key);
            dominators.put(key, strict);
        }
    }
    private void validateBranches(String key, List<WorkflowEdgeSpec> branches) {
        if (branches.stream().filter(WorkflowEdgeSpec::defaultBranch).count() != 1)
            fail("CONDITION 必须恰有一个默认分支: " + key);
        Set<String> labels = new HashSet<>();
        for (var edge : branches) {
            if (edge.defaultBranch()) {
                if (edge.condition() != null && !edge.condition().isBlank()) fail("默认分支不能配置条件: " + key);
            } else {
                String label = edge.condition() == null ? "" : edge.condition().toLowerCase(Locale.ROOT);
                if (!Set.of("true", "false").contains(label) || !labels.add(label))
                    fail("CONDITION 分支条件须为唯一的 true/false: " + key);
            }
        }
    }
    private List<String> templates(WorkflowNodeSpec node) {
        return switch (type(node)) {
            case "TEMPLATE" -> List.of(required(node, "template"));
            case "CONDITION" -> WorkflowExpression.parse(required(node, "expression")).templates();
            case "KNOWLEDGE" -> {
                required(node, "knowledgeBaseId");
                JsonNode topK = node.config().get("topK");
                if (topK != null && (!topK.isIntegralNumber() || !topK.canConvertToInt() || topK.asInt() < 1))
                    fail("KNOWLEDGE topK 必须是正整数");
                yield List.of(required(node, "query"));
            }
            case "END" -> List.of(required(node, "output"));
            default -> List.of();
        };
    }
    private String output(WorkflowNodeSpec node) {
        if (type(node).equals("START")) return "userMessage";
        if (type(node).equals("END")) return null;
        var value = node.config().get("outputVariable");
        if (value != null && !value.isTextual()) fail("输出变量名必须是文本: " + node.nodeKey());
        if (value == null || value.asText().isBlank()) return type(node).equals("KNOWLEDGE") ? "citations" : "result";
        return value.asText();
    }
    private String required(WorkflowNodeSpec node, String field) {
        var value = node.config().get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) fail("节点缺少文本配置: " + node.nodeKey() + "." + field);
        return value.asText();
    }
    private String type(WorkflowNodeSpec node) { return node.type() == null ? "" : node.type().toUpperCase(Locale.ROOT); }
    private void fail(String message) { throw new BizException(ErrorCode.PARAM_ERROR, message); }
}
