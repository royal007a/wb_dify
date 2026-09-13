package com.hify.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockModelClient implements ModelClient {
    private static final Pattern EXPRESSION = Pattern.compile("(-?\\d+(?:\\.\\d+)?\\s*[+\\-*/]\\s*-?\\d+(?:\\.\\d+)?)");

    @Override
    public RuntimeMessage generate(ModelRequest request) {
        RuntimeMessage last = request.messages().get(request.messages().size() - 1);
        if ("tool".equals(last.role())) {
            return RuntimeMessage.assistant("工具执行完成，结果是：" + last.content());
        }

        String text = last.content() == null ? "" : last.content();
        if (hasTool(request.tools(), "current_time") &&
                (text.contains("时间") || text.toLowerCase().contains("time"))) {
            return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                    UUID.randomUUID().toString(), "current_time", Map.of())));
        }

        Matcher matcher = EXPRESSION.matcher(text);
        if (hasTool(request.tools(), "calculator") && matcher.find()) {
            return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                    UUID.randomUUID().toString(), "calculator", Map.of("expression", matcher.group(1)))));
        }

        return RuntimeMessage.assistant("Hify Demo Agent 已收到：" + text +
                "\n\n你可以问我“现在几点？”或“计算 12.5 * 4”来验证工具调用闭环。");
    }

    private boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> name.equals(tool.name()));
    }
}

