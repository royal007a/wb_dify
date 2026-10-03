package com.hify.runtime;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockModelClient implements ModelClient {
    // Demo-only intent matching; production models still emit their own structured tool calls.
    private static final Pattern TIME_REQUEST = Pattern.compile("时间|几点|日期|几号|\\btime\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPRESSION = Pattern.compile("(-?\\d+(?:\\.\\d+)?\\s*[+\\-*/]\\s*-?\\d+(?:\\.\\d+)?)");

    @Override
    public RuntimeMessage generate(ModelRequest request) {
        RuntimeMessage last = request.messages().get(request.messages().size() - 1);
        if ("tool".equals(last.role())) {
            return RuntimeMessage.assistant("工具执行完成，结果是：" + last.content());
        }

        String text = last.content() == null ? "" : last.content();
        if (hasTool(request.tools(), "current_time") && TIME_REQUEST.matcher(text).find()) {
            return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                    UUID.randomUUID().toString(), "current_time", Map.of())));
        }

        Matcher matcher = EXPRESSION.matcher(text);
        if (hasTool(request.tools(), "calculator") && matcher.find()) {
            return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                    UUID.randomUUID().toString(), "calculator", Map.of("expression", matcher.group(1)))));
        }

        List<String> examples = new ArrayList<>();
        if (hasTool(request.tools(), "current_time")) examples.add("“现在几点？”");
        if (hasTool(request.tools(), "calculator")) examples.add("“计算 12.5 * 4”");
        String help = examples.isEmpty() ? "当前版本没有绑定可演示的时间或计算工具。"
                : "可以试试 " + String.join(" 或 ", examples) + "，验证工具调用。";
        return RuntimeMessage.assistant("当前是 Hify Demo Agent（本地规则模拟，未调用真实大模型）。\n\n"
                + help + "通用问答请配置真实模型并发布 Agent，再新建会话。");
    }

    private boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> name.equals(tool.name()));
    }
}
