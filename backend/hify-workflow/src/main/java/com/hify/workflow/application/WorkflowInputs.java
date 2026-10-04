package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.TextInput;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.api.WorkflowNodeSpec;
import java.math.BigDecimal;
import java.util.*;

/** Bounded scalar inputs, not general JSON Schema. No coercion and no template evaluation. */
final class WorkflowInputs {
    private static final Set<String> FIELDS = Set.of("name", "label", "type", "required", "default", "maxLength", "options");
    private static final BigDecimal MAX_NUMBER = new BigDecimal("1000000000000");
    private WorkflowInputs() {}

    static boolean declared(WorkflowNodeSpec node) {
        return "START".equalsIgnoreCase(node.type()) && node.config() != null && node.config().has("inputs");
    }

    static List<JsonNode> schema(WorkflowNodeSpec start) {
        JsonNode raw = start.config().get("inputs");
        if (raw == null) return List.of();
        TextInput.requireNoNulInJson(raw);
        if (!raw.isArray() || raw.size() > 16) throw invalid("START inputs 必须是最多16项的数组");
        Set<String> names = new HashSet<>();
        List<JsonNode> fields = new ArrayList<>();
        for (JsonNode field : raw) {
            if (!field.isObject()) throw invalid("输入字段必须是对象");
            field.fieldNames().forEachRemaining(key -> {
                if (!FIELDS.contains(key)) throw invalid("输入schema包含未知属性");
            });
            String name = field.path("name").asText("");
            if (!field.path("name").isTextual() || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                    || name.equals("userMessage") || !names.add(name)) throw invalid("输入字段名无效或重复");
            if (field.has("label") && (!field.path("label").isTextual() || field.path("label").asText().isBlank()
                    || field.path("label").asText().length() > 128)) throw invalid("输入label须为1至128字符");
            String type = field.path("type").asText("");
            if (!Set.of("text", "number", "boolean", "enum").contains(type)) throw invalid("输入类型不支持");
            if (!field.path("required").isBoolean()) throw invalid("输入required须为布尔值");
            if (field.path("required").booleanValue() == field.has("default"))
                throw invalid("必填输入不能有默认值，可选输入必须有默认值");
            if (field.has("maxLength") && (!type.equals("text") || !field.path("maxLength").isIntegralNumber()
                    || !field.path("maxLength").canConvertToInt() || field.path("maxLength").intValue() < 1
                    || field.path("maxLength").intValue() > 4000)) throw invalid("文本maxLength须为1至4000");
            if (type.equals("enum")) {
                JsonNode options = field.path("options");
                if (!options.isArray() || options.isEmpty() || options.size() > 32) throw invalid("枚举须有1至32个选项");
                Set<String> unique = new HashSet<>();
                for (JsonNode option : options) if (!option.isTextual() || option.asText().isBlank()
                        || option.asText().length() > 128 || !unique.add(option.asText())) throw invalid("枚举选项无效或重复");
            } else if (field.has("options")) throw invalid("非枚举输入不能配置options");
            if (field.has("default")) value(field, field.get("default"));
            fields.add(field);
        }
        return List.copyOf(fields);
    }

    static Set<String> variables(WorkflowNodeSpec start) {
        Set<String> result = new HashSet<>(Set.of("userMessage"));
        schema(start).forEach(field -> result.add(field.path("name").asText()));
        return Set.copyOf(result);
    }

    static Map<String, Object> bind(WorkflowNodeSpec start, JsonNode inputs) {
        TextInput.requireNoNulInJson(inputs);
        if (inputs != null && (!inputs.isObject() || inputs.size() > 16)) throw invalid("inputs须为最多16字段的对象");
        List<JsonNode> fields = schema(start);
        Set<String> names = new HashSet<>();
        fields.forEach(field -> names.add(field.path("name").asText()));
        if (inputs != null) inputs.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw invalid("inputs包含未声明的字段");
        });
        Map<String, Object> result = new LinkedHashMap<>();
        for (JsonNode field : fields) {
            String name = field.path("name").asText();
            JsonNode supplied = inputs == null ? null : inputs.get(name);
            if (supplied == null) supplied = field.get("default");
            if (supplied == null) throw invalid("缺少必填输入: " + name);
            result.put(name, value(field, supplied));
        }
        return Collections.unmodifiableMap(result);
    }

    static boolean chatCompatible(WorkflowDraftRequest graph) {
        return graph.nodes().stream().filter(n -> "START".equalsIgnoreCase(n.type()))
                .flatMap(n -> schema(n).stream()).noneMatch(field -> field.path("required").booleanValue());
    }

    static void requireChatCompatible(WorkflowDraftRequest graph) {
        if (!chatCompatible(graph)) throw new BizException(ErrorCode.CONFLICT,
                "Workflow 包含必填具名输入，当前Agent Chat仅支持userMessage；请使用试运行表单或配置可选默认值");
    }

    private static Object value(JsonNode field, JsonNode value) {
        String type = field.path("type").asText();
        if (value == null || value.isNull()) throw invalid("输入值不能为null");
        switch (type) {
            case "text":
                if (!value.isTextual() || value.asText().length() > field.path("maxLength").asInt(2000)
                        || (field.path("required").booleanValue() && value.asText().isBlank())) break;
                return value.asText();
            case "enum":
                if (value.isTextual()) for (JsonNode option : field.path("options")) if (option.equals(value)) return value.asText();
                break;
            case "boolean":
                if (value.isBoolean()) return value.booleanValue();
                break;
            case "number":
                if (value.isNumber() && Double.isFinite(value.doubleValue()) && value.decimalValue().abs().compareTo(MAX_NUMBER) <= 0)
                    return value.decimalValue();
                break;
            default: break;
        }
        throw invalid("输入类型或取值不符合schema: " + field.path("name").asText());
    }

    private static BizException invalid(String message) { return new BizException(ErrorCode.PARAM_ERROR, message); }
}
