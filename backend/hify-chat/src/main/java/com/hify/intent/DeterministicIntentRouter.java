package com.hify.intent;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DeterministicIntentRouter {
    private static final Set<String> HELP = Set.of(
            "帮助", "帮忙", "怎么用", "你会什么", "能做什么", "/help", "help", "?", "？",
            "请告诉我怎么使用", "有哪些能力", "使用说明", "可以帮我干嘛");
    private static final Set<String> CANCEL = Set.of(
            "取消", "停止", "停下", "别做了", "不用了", "中止", "终止", "/cancel", "cancel",
            "把刚才那个停掉", "别再继续执行了", "先暂停一下");
    private static final Pattern DANGEROUS_ACTION = Pattern.compile(
            "删除|删掉|删光|清空|重置|覆盖|销毁", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_SCOPE = Pattern.compile(
            "全部|所有|数据库|项目|工作流|agent|智能体|数据", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL_COMMAND = Pattern.compile(
            "^(?:请)?(?:取消|停止|停下|中止|终止|暂停)(?:当前|这个|那个|刚才)?(?:任务|运行|执行)?(?:一下)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKFLOW_COMMAND = Pattern.compile(
            "^(?:/(?:workflow|wf)|运行工作流|执行工作流)(?:\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile(
            ".*(现在几点|当前时间|现在时间|几点了|今天日期|今天几号|日期和时间|几月几日|系统时钟|北京时间|时间戳|current time).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPRESSION = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?\\s*[+\\-*/]\\s*-?\\d+(?:\\.\\d+)?)");
    private static final Pattern CALCULATE_WITHOUT_EXPRESSION = Pattern.compile(
            ".*(计算|算一下|帮我算|calculator).*", Pattern.CASE_INSENSITIVE);

    public Optional<IntentDecision> route(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return Optional.of(clarify("unknown", 1, normalizedForDecision(normalized), Map.of(),
                    List.of("input"), "empty_input", "input.non_empty"));
        }

        String comparable = normalized.toLowerCase(Locale.ROOT);
        if (CANCEL.contains(comparable) || CANCEL_COMMAND.matcher(normalized).matches()) {
            return Optional.of(tool("cancel_run", normalized, "cancel_run", Map.of(), "command.cancel"));
        }
        if (HELP.contains(comparable)) {
            return Optional.of(tool("show_help", normalized, "show_help", Map.of(), "command.help"));
        }
        if (DANGEROUS_ACTION.matcher(normalized).find() && DANGEROUS_SCOPE.matcher(normalized).find()) {
            return Optional.of(clarify("dangerous_action", 1, normalized,
                    Map.of("requestedAction", normalized), List.of("confirmation"),
                    "dangerous_action_requires_confirmation", "safety.dangerous_action"));
        }

        Matcher workflow = WORKFLOW_COMMAND.matcher(normalized);
        if (workflow.matches()) {
            String workflowId = workflow.group(1);
            if (workflowId == null || workflowId.isBlank()) {
                return Optional.of(clarify("run_workflow", 1, normalized, Map.of(),
                        List.of("workflowId"), "required_slot_missing", "workflow.workflow_id"));
            }
            return Optional.of(new IntentDecision("run_workflow", 1, normalized,
                    Map.of("workflowId", workflowId.trim()), List.of(), IntentRoute.WORKFLOW,
                    evidence("RULE", "workflow.exact_command", workflow.group()), "exact_workflow_command"));
        }

        if (TIME.matcher(normalized).matches()) {
            return Optional.of(tool("current_time", normalized, "current_time", Map.of(), "tool.current_time"));
        }

        Matcher expression = EXPRESSION.matcher(normalized.replace('×', '*').replace('÷', '/'));
        if (expression.find()) {
            return Optional.of(tool("calculate", normalized, "calculator",
                    Map.of("expression", expression.group(1).replaceAll("\\s+", "")), "tool.calculator_expression"));
        }
        if (CALCULATE_WITHOUT_EXPRESSION.matcher(normalized).matches()) {
            return Optional.of(clarify("calculate", 1, normalized, Map.of("toolName", "calculator"),
                    List.of("expression"), "required_slot_missing", "tool.calculator_missing_expression"));
        }
        if (normalized.startsWith("/")) {
            return Optional.of(new IntentDecision("unknown", 1, normalized, Map.of(), List.of(),
                    IntentRoute.UNKNOWN, evidence("RULE", "command.unsupported", normalized),
                    "unsupported_exact_command"));
        }
        return Optional.empty();
    }

    public String normalize(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    private IntentDecision tool(String intent, String normalized, String toolName,
                                Map<String, Object> extraSlots, String rule) {
        java.util.LinkedHashMap<String, Object> slots = new java.util.LinkedHashMap<>();
        slots.put("toolName", toolName);
        slots.putAll(extraSlots);
        return new IntentDecision(intent, 1, normalized, slots, List.of(), IntentRoute.TOOL,
                evidence("RULE", rule, normalized), "deterministic_rule_match");
    }

    private IntentDecision clarify(String intent, double confidence, String normalized,
                                   Map<String, Object> slots, List<String> missingSlots,
                                   String reason, String rule) {
        return new IntentDecision(intent, confidence, normalized, slots, missingSlots,
                IntentRoute.CLARIFY, evidence("RULE", rule, normalized), reason);
    }

    private List<IntentEvidence> evidence(String source, String reference, String detail) {
        return List.of(new IntentEvidence(source, reference, detail));
    }

    private String normalizedForDecision(String normalized) {
        return normalized.isBlank() ? "<empty>" : normalized;
    }
}
