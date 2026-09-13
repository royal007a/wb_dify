package com.hify.runtime;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ToolRuntime {
    private static final Pattern CALCULATION = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    public ToolRuntime() {
        definitions.put("current_time", new ToolDefinition(
                "current_time", "Get the current time in an ISO-8601 offset format",
                Map.of("type", "object", "properties", Map.of()), "read"));
        definitions.put("calculator", new ToolDefinition(
                "calculator", "Calculate a two-operand arithmetic expression such as 12.5*4",
                Map.of(
                        "type", "object",
                        "properties", Map.of("expression", Map.of("type", "string")),
                        "required", List.of("expression")
                ), "read"));
    }

    public List<ToolDefinition> definitions(Set<String> enabledNames) {
        return definitions.values().stream().filter(tool -> enabledNames.contains(tool.name())).toList();
    }

    public ExecutionResult execute(RuntimeMessage.ToolCall call, Set<String> enabledNames) {
        if (!enabledNames.contains(call.name()) || !definitions.containsKey(call.name())) {
            return ExecutionResult.permissionDenied("Tool is not enabled: " + call.name());
        }
        ToolDefinition definition = definitions.get(call.name());
        if (!"read".equals(definition.risk())) {
            return ExecutionResult.permissionDenied("Tool risk is not allowed: " + definition.risk());
        }
        try {
            validateSchema(definition, call.arguments());
            ExecutionResult result = switch (call.name()) {
                case "current_time" -> ExecutionResult.success(OffsetDateTime.now().toString());
                case "calculator" -> ExecutionResult.success(calculate(call.arguments()));
                default -> ExecutionResult.error("Unknown tool: " + call.name(), true);
            };
            return result.limit(32 * 1024);
        } catch (IllegalArgumentException exception) {
            return ExecutionResult.error(exception.getMessage(), false);
        } catch (RuntimeException exception) {
            return ExecutionResult.error("Tool execution failed", true);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateSchema(ToolDefinition definition, Map<String, Object> arguments) {
        if (arguments == null) throw new IllegalArgumentException("Tool arguments are required");
        Object requiredValue = definition.inputSchema().get("required");
        if (requiredValue instanceof List<?> required) {
            for (Object key : required) {
                if (!arguments.containsKey(String.valueOf(key))) {
                    throw new IllegalArgumentException("Missing required argument: " + key);
                }
            }
        }
        Object propertiesValue = definition.inputSchema().get("properties");
        if (propertiesValue instanceof Map<?, ?> properties) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                Object property = properties.get(entry.getKey());
                if (property instanceof Map<?, ?> schema && "string".equals(schema.get("type"))
                        && !(entry.getValue() instanceof String)) {
                    throw new IllegalArgumentException(entry.getKey() + " must be a string");
                }
            }
        }
    }

    private String calculate(Map<String, Object> arguments) {
        Object raw = arguments.get("expression");
        if (!(raw instanceof String expression)) {
            throw new IllegalArgumentException("expression must be a string");
        }
        Matcher matcher = CALCULATION.matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Only two-operand arithmetic is supported in the MVP");
        }
        BigDecimal left = new BigDecimal(matcher.group(1));
        BigDecimal right = new BigDecimal(matcher.group(3));
        BigDecimal value = switch (matcher.group(2)) {
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            case "*" -> left.multiply(right);
            case "/" -> {
                if (BigDecimal.ZERO.compareTo(right) == 0) {
                    throw new IllegalArgumentException("Division by zero is not allowed");
                }
                yield left.divide(right, MathContext.DECIMAL64);
            }
            default -> throw new IllegalArgumentException("Unsupported operator");
        };
        return value.stripTrailingZeros().toPlainString();
    }

    public record ExecutionResult(Object value, boolean error, boolean fatal, boolean permissionDenied) {
        public static ExecutionResult success(Object value) {
            return new ExecutionResult(value, false, false, false);
        }
        public static ExecutionResult error(String message, boolean fatal) {
            return new ExecutionResult(message, true, fatal, false);
        }
        public static ExecutionResult permissionDenied(String message) {
            return new ExecutionResult(message, true, true, true);
        }
        public ExecutionResult limit(int maxCharacters) {
            String text = String.valueOf(value);
            if (text.length() <= maxCharacters) return this;
            return new ExecutionResult(text.substring(0, maxCharacters) + "…[truncated]",
                    error, fatal, permissionDenied);
        }
    }
}
