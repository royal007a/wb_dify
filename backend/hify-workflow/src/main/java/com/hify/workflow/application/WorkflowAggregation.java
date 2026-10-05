package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.WorkflowNodeSpec;
import java.util.*;

/** A scalar merge on one executed branch, never a parallel join or first-present fallback. */
final class WorkflowAggregation {
    private static final Set<String> FIELDS = Set.of("candidates", "outputVariable", "__ui");
    private WorkflowAggregation() {}

    static List<String> candidates(WorkflowNodeSpec node) {
        node.config().fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw invalid("聚合配置包含未知字段");
        });
        var raw = node.config().get("candidates");
        if (raw == null || !raw.isArray() || raw.size() < 2 || raw.size() > 16)
            throw invalid("聚合候选必须为2至16项数组");
        List<String> result = new ArrayList<>();
        Set<String> owners = new HashSet<>();
        for (var value : raw) {
            if (!value.isTextual()) throw invalid("聚合候选必须是node.variable文本");
            String ref = value.textValue();
            String[] parts = ref.split("\\.", -1);
            if (parts.length != 2 || !WorkflowTemplates.identifier(parts[0])
                    || !WorkflowTemplates.identifier(parts[1]) || !owners.add(parts[0]))
                throw invalid("聚合候选格式无效或生产者重复");
            result.add(ref);
        }
        return List.copyOf(result);
    }

    static void validate(WorkflowNodeSpec aggregate, List<String> order,
                         Map<String, WorkflowNodeSpec> nodes, Map<String, Set<String>> predecessors,
                         Map<String, Set<String>> outputs) {
        Set<String> ancestors = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(predecessors.getOrDefault(aggregate.nodeKey(), Set.of()));
        while (!pending.isEmpty()) {
            String key = pending.remove();
            if (ancestors.add(key)) pending.addAll(predecessors.getOrDefault(key, Set.of()));
        }
        Set<String> owners = new HashSet<>();
        for (String ref : candidates(aggregate)) {
            String[] parts = ref.split("\\.");
            if (!ancestors.contains(parts[0]) || !outputs.getOrDefault(parts[0], Set.of()).contains(parts[1]))
                throw invalid("聚合候选必须是已声明的严格上游输出");
            if ("KNOWLEDGE".equalsIgnoreCase(nodes.get(parts[0]).type()))
                throw invalid("聚合仅支持标量，不支持知识引用列表");
            owners.add(parts[0]);
        }
        // Keep all possible counts, not only max: {0,1} must not be accepted as {1}.
        Map<String, Set<Integer>> counts = new HashMap<>();
        for (String key : order) {
            Set<String> parents = predecessors.getOrDefault(key, Set.of());
            Set<Integer> incoming = new HashSet<>();
            if (parents.isEmpty()) incoming.add(0);
            else for (String parent : parents) incoming.addAll(counts.get(parent));
            if (key.equals(aggregate.nodeKey())) {
                if (!incoming.equals(Set.of(1))) throw invalid("到达聚合的每条结构路径必须恰有一个候选");
                return;
            }
            Set<Integer> outgoing = new HashSet<>();
            for (int count : incoming) outgoing.add(Math.min(2, count + (owners.contains(key) ? 1 : 0)));
            counts.put(key, outgoing);
        }
        throw invalid("聚合节点不在拓扑序中");
    }

    static Object select(WorkflowNodeSpec aggregate, Map<String, Object> context) {
        Object selected = null;
        int found = 0;
        for (String ref : candidates(aggregate)) {
            if (!context.containsKey(ref)) continue;
            found++;
            selected = context.get(ref);
        }
        if (found != 1 || selected == null || !(selected instanceof String || selected instanceof Boolean
                || selected instanceof java.math.BigDecimal || selected instanceof Integer || selected instanceof Long))
            throw new BizException(ErrorCode.CONFLICT, "聚合须恰有一个已执行且非空值的标量候选");
        return selected;
    }

    private static BizException invalid(String message) { return new BizException(ErrorCode.PARAM_ERROR, message); }
}
