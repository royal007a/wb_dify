package com.hify.runtime;

import com.hify.common.ExecutionControl;
import com.hify.common.ExecutionCancelledException;
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
        return execute(call, enabledNames, ExecutionControl.none());
    }

    public ExecutionResult execute(RuntimeMessage.ToolCall call, Set<String> enabledNames,
                                   ExecutionControl control) {
        control.throwIfCancelled();
        if (!definitions.containsKey(call.name())) {
            return ExecutionResult.unavailable("Tool is not available: " + call.name());
        }
        if (!enabledNames.contains(call.name())) {
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
                default -> ExecutionResult.executionFailed("Unknown tool: " + call.name(), true);
            };
            control.throwIfCancelled();
            return result.limit(32 * 1024);
        } catch (ExecutionCancelledException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            return ExecutionResult.invalidArguments(exception.getMessage());
        } catch (RuntimeException exception) {
            return ExecutionResult.executionFailed("Tool execution failed", true);
        }
    }

    public java.util.Optional<String> findReadOnlyAlternative(String requestedName, Set<String> enabledNames) {
        String alternative = switch (requestedName) {
            case "calculate", "math" -> "calculator";
            case "clock", "get_time" -> "current_time";
            default -> null;
        };
        if (alternative == null || !enabledNames.contains(alternative)) return java.util.Optional.empty();
        ToolDefinition definition = definitions.get(alternative);
        return definition != null && "read".equals(definition.risk())
                ? java.util.Optional.of(alternative) : java.util.Optional.empty();
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

    public enum FailureType {
        NONE,
        INVALID_ARGUMENTS,
        TOOL_UNAVAILABLE,
        PERMISSION_DENIED,
        EXECUTION_FAILED,
        CANCELLED
    }

    public record ExecutionResult(Object value, boolean error, boolean fatal,
                                  boolean permissionDenied, FailureType failureType) {
        public static ExecutionResult success(Object value) {
            return new ExecutionResult(value, false, false, false, FailureType.NONE);
        }
        public static ExecutionResult invalidArguments(String message) {
            return new ExecutionResult(message, true, false, false, FailureType.INVALID_ARGUMENTS);
        }
        public static ExecutionResult unavailable(String message) {
            return new ExecutionResult(message, true, false, false, FailureType.TOOL_UNAVAILABLE);
        }
        public static ExecutionResult executionFailed(String message, boolean fatal) {
            return new ExecutionResult(message, true, fatal, false, FailureType.EXECUTION_FAILED);
        }
        public static ExecutionResult permissionDenied(String message) {
            return new ExecutionResult(message, true, true, true, FailureType.PERMISSION_DENIED);
        }
        public static ExecutionResult cancelled() {
            return new ExecutionResult("Tool execution cancelled", true, true, false, FailureType.CANCELLED);
        }
        public static ExecutionResult skipped(String message) {
            return new ExecutionResult(message, true, false, false, FailureType.EXECUTION_FAILED);
        }
        public ExecutionResult limit(int maxCharacters) {
            String text = String.valueOf(value);
            if (text.length() <= maxCharacters) return this;
            return new ExecutionResult(text.substring(0, maxCharacters) + "…[truncated]",
                    error, fatal, permissionDenied, failureType);
        }
    }
}
