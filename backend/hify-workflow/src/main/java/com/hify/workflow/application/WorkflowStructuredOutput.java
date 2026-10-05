package com.hify.workflow.application;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit bounded schema subset; never changes the shared mapper or repairs model output. */
final class WorkflowStructuredOutput {
    private static final int MAX_BYTES = 32768;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                    .maxNumberLength(128).maxStringLength(8192).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final ObjectWriter PLAIN = JSON.writer().with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    private WorkflowStructuredOutput() {}

    static boolean declared(WorkflowNodeSpec node) {
        return node.type().equalsIgnoreCase("LLM") && node.config().has("outputSchema");
    }
    static void validate(WorkflowNodeSpec node) {
        if (!declared(node)) return;
        JsonNode s = node.config().get("outputSchema");
        keys(s, Set.of("type", "properties", "required", "additionalProperties"), Set.of("type", "properties", "required", "additionalProperties"));
        if (!s.path("type").isTextual() || !s.path("type").textValue().equals("object")
                || !s.path("additionalProperties").isBoolean() || s.path("additionalProperties").booleanValue()) throw invalid();
        JsonNode props = s.get("properties"), required = s.get("required");
        if (!props.isObject() || props.size() < 1 || props.size() > 16 || !required.isArray()) throw invalid();
        Set<String> names = new LinkedHashSet<>();
        props.fields().forEachRemaining(e -> {
            String name = e.getKey();
            if (name.length() > 64 || !WorkflowTemplates.identifier(name) || name.equals(primary(node))) throw invalid();
            names.add(name); property(e.getValue());
        });
        Set<String> listed = new HashSet<>();
        for (JsonNode n : required) if (!n.isTextual() || !listed.add(n.textValue())) throw invalid();
        if (!names.equals(listed) || utf8(s.toString()) > 8192) throw invalid();
    }
    private static void property(JsonNode p) {
        if (p == null || !p.isObject() || !p.path("type").isTextual()) throw invalid();
        switch (p.path("type").textValue()) {
            case "string" -> {
                keys(p, Set.of("type", "maxLength"), Set.of("type")); bound(p, "maxLength", 2000);
            }
            case "number", "boolean" -> keys(p, Set.of("type"), Set.of("type"));
            case "array" -> {
                keys(p, Set.of("type", "maxItems", "items"), Set.of("type", "items"));
                bound(p, "maxItems", 20);
                JsonNode item = p.get("items");
                if (!item.isObject() || !item.path("type").isTextual() || !item.path("type").textValue().equals("string")) throw invalid();
                property(item);
            }
            default -> throw invalid();
        }
    }
    private static void keys(JsonNode n, Set<String> allowed, Set<String> required) {
        if (n == null || !n.isObject()) throw invalid();
        n.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalid(); });
        if (!required.stream().allMatch(n::has)) throw invalid();
    }
    private static int bound(JsonNode p, String field, int maximum) {
        JsonNode n = p.get(field);
        if (n == null) return maximum;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 0 || n.intValue() > maximum) throw invalid();
        return n.intValue();
    }
    static Set<String> fields(WorkflowNodeSpec node) {
        if (!declared(node)) return Set.of();
        validate(node);
        Set<String> fields = new LinkedHashSet<>();
        node.config().path("outputSchema").path("properties").fieldNames().forEachRemaining(fields::add);
        return Collections.unmodifiableSet(fields);
    }
    static String fieldType(WorkflowNodeSpec node, String field) {
        return declared(node) ? node.config().path("outputSchema").path("properties").path(field).path("type").asText("") : "";
    }
    static String primary(WorkflowNodeSpec node) {
        String name = node.config().path("outputVariable").asText("");
        return name.isBlank() ? "result" : name;
    }
    static String systemPrompt(WorkflowNodeSpec node, String system) {
        if (!declared(node)) return system;
        validate(node);
        return system + "\nReturn exactly one JSON object matching this schema. No markdown fences, commentary or extra keys. All fields are required.\n"
                + node.config().get("outputSchema");
    }

    /** Return a complete validated bundle. The caller must not bind any field before this returns. */
    static Map<String, Object> decode(WorkflowNodeSpec node, String raw) {
        validate(node);
        try {
            if (raw == null || utf8(raw) > MAX_BYTES) throw rejected();
            JsonNode value = JSON.readTree(raw), props = node.config().path("outputSchema").path("properties");
            if (value == null || !value.isObject() || value.size() != props.size()) throw rejected();
            Map<String, Object> output = new LinkedHashMap<>();
            ObjectNode canonical = JSON.createObjectNode();
            var entries = props.fields();
            while (entries.hasNext()) {
                var e = entries.next(); String name = e.getKey(); JsonNode rule = e.getValue(), v = value.get(name);
                if (v == null || v.isNull()) throw rejected();
                switch (rule.path("type").textValue()) {
                    case "string" -> { String text = string(v, bound(rule, "maxLength", 2000)); output.put(name, text); canonical.put(name, text); }
                    case "boolean" -> {
                        if (!v.isBoolean()) throw rejected();
                        output.put(name, v.booleanValue()); canonical.put(name, v.booleanValue());
                    }
                    case "number" -> {
                        if (!v.isNumber()) throw rejected();
                        BigDecimal d = v.decimalValue();
                        // Check before any normalization or plain rendering (huge exponents are short tokens).
                        if (d.precision() > 38 || d.scale() < -100 || d.scale() > 100) throw rejected();
                        d = d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
                        output.put(name, d); canonical.set(name, DecimalNode.valueOf(d));
                    }
                    case "array" -> {
                        if (!v.isArray() || v.size() > bound(rule, "maxItems", 20)) throw rejected();
                        ArrayNode a = JSON.createArrayNode();
                        for (JsonNode item : v) a.add(string(item, bound(rule.get("items"), "maxLength", 2000)));
                        output.put(name, a); canonical.set(name, a);
                    }
                    default -> throw rejected();
                }
            }
            String text = PLAIN.writeValueAsString(canonical);
            if (utf8(text) > MAX_BYTES) throw rejected();
            output.put(primary(node), text);
            return Collections.unmodifiableMap(output);
        } catch (Exception failure) {
            // Never persist/return parser messages containing untrusted model content.
            throw rejected();
        }
    }
    private static String string(JsonNode n, int max) {
        if (!n.isTextual() || n.textValue().length() > max || n.textValue().indexOf('\0') >= 0) throw rejected();
        return n.textValue();
    }
    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static WorkflowDefinitionException invalid() { return new WorkflowDefinitionException("LLM outputSchema 必须符合有界 schema 契约"); }
    private static BizException rejected() { return new BizException(ErrorCode.CONFLICT, "LLM 结构化输出不符合发布 schema，工作流已停止"); }
}
