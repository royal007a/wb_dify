package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.workflow.api.WorkflowNodeSpec;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowStructuredOutputTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static WorkflowNodeSpec node(String properties, String required) throws Exception {
        var c = JSON.createObjectNode().put("providerId", "p").put("modelId", "m").put("prompt", "Extract");
        c.set("outputSchema", JSON.readTree("{\"type\":\"object\",\"properties\":" + properties
                + ",\"required\":" + required + ",\"additionalProperties\":false}"));
        return new WorkflowNodeSpec("extract", "LLM", "extract", c);
    }
    static WorkflowNodeSpec allTypes() throws Exception {
        return node("{\"name\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"},\"ok\":{\"type\":\"boolean\"},"
                + "\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}", "[\"name\",\"amount\",\"ok\",\"questions\"]");
    }
    private static void bad(WorkflowNodeSpec n, String raw) {
        assertThatThrownBy(() -> WorkflowStructuredOutput.decode(n, raw)).isInstanceOf(BizException.class)
                .hasMessage("LLM 结构化输出不符合发布 schema，工作流已停止");
    }
    @Test void preciseScalarsArraysAndFalsyValuesHaveStableTypedOutputs() throws Exception {
        var n = allTypes();
        var values = WorkflowStructuredOutput.decode(n,
                "{\"name\":\"\",\"amount\":0.12345678901234567890123,\"ok\":false,\"questions\":[\"a,b\",\"{{secret.value}}\"]}");
        assertThat(values.get("name")).isEqualTo("");
        assertThat(values.get("ok")).isEqualTo(false);
        assertThat(values.get("amount")).isEqualTo(new BigDecimal("0.12345678901234567890123"));
        assertThat(values.get("questions")).isInstanceOf(JsonNode.class);
        var ctx = new WorkflowExecutionContext("test");
        values.forEach((k, v) -> ctx.set(n.nodeKey(), k, v));
        assertThat(ctx.resolve("{{extract.amount}}|{{extract.questions}}"))
                .isEqualTo("0.12345678901234567890123|[\"a,b\",\"{{secret.value}}\"]");
        assertThat(JSON.readTree((String) values.get("result")).path("questions")).hasSize(2);
        var zero = WorkflowStructuredOutput.decode(n, "{\"name\":\"\",\"amount\":0,\"ok\":false,\"questions\":[]}");
        assertThat((BigDecimal) zero.get("amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((JsonNode) zero.get("questions")).isEmpty();
    }
    @Test void exactObjectKeysTypesAndNullAreEnforcedWithoutLeakingRawReply() throws Exception {
        var n = node("{\"x\":{\"type\":\"number\"}}", "[\"x\"]");
        for (String raw : List.of("{}", "null", "[]", "{\"x\":null}", "{\"x\":\"secret-value\"}", "{\"x\":true}",
                "{\"x\":1,\"secret-value\":2}", "{\"x\":{\"secret-value\":2}}")) bad(n, raw);
        assertThat(WorkflowStructuredOutput.decode(n, "{\"x\":1}")).containsKey("result");
        bad(node("{\"b\":{\"type\":\"boolean\"}}", "[\"b\"]"), "{\"b\":\"false\"}");
        bad(node("{\"s\":{\"type\":\"string\"}}", "[\"s\"]"), "{\"s\":1}");
    }
    @Test void rejectsDuplicateKeysTrailingValuesCodeFencesAndPermissiveJson() throws Exception {
        var n = node("{\"x\":{\"type\":\"number\"}}", "[\"x\"]");
        for (String raw : List.of("{\"x\":1,\"x\":2}", "{\"x\":1}{}", "{\"x\":1} null", "{\"x\":1} junk",
                "```json\n{\"x\":1}\n```", "{\"x\":NaN}", "{\"x\":Infinity}", "{\"x\":01}",
                "{\"x\":1,}", "{/* secret */\"x\":1}", "{'x':1}", "{x:1}", "", " ")) bad(n, raw);
        assertThat(WorkflowStructuredOutput.decode(n, " \n{\"x\":1}\n ")).containsKey("x");
    }
    @Test void enforcesStringUtf16ArrayAndRawByteLimits() throws Exception {
        var n = node("{\"s\":{\"type\":\"string\",\"maxLength\":2}}", "[\"s\"]");
        assertThat(WorkflowStructuredOutput.decode(n, "{\"s\":\"😀\"}")).containsEntry("s", "😀");
        bad(n, "{\"s\":\"😀x\"}");
        var a = node("{\"a\":{\"type\":\"array\",\"maxItems\":2,\"items\":{\"type\":\"string\",\"maxLength\":2}}}", "[\"a\"]");
        assertThat((JsonNode) WorkflowStructuredOutput.decode(a, "{\"a\":[\"ab\",\"\"]}").get("a")).hasSize(2);
        for (String raw : List.of("{\"a\":[\"a\",\"b\",\"c\"]}", "{\"a\":[null]}", "{\"a\":[1]}", "{\"a\":[[]]}", "{\"a\":[\"abc\"]}")) bad(a, raw);
        String small = "{\"s\":\"a\"}";
        assertThat(WorkflowStructuredOutput.decode(n, small + " ".repeat(32768-small.length()))).containsEntry("s", "a");
        bad(n, small + " ".repeat(32769-small.length()));
        bad(n, "{\"s\":\"" + "汉".repeat(11000) + "\"}");
        bad(n, "{\"s\":\"\\u0000\"}");
    }
    @Test void decimalBudgetRejectsHugeExponentsAndPrecision() throws Exception {
        var n = node("{\"x\":{\"type\":\"number\"}}", "[\"x\"]");
        for (String number : List.of("1e101", "1e-101", "1e-999999999", "123456789012345678901234567890123456789", "1".repeat(129)))
            bad(n, "{\"x\":"+number+"}");
        for (String number : List.of("1e100", "1e-100", "-0.0", "12345678901234567890123456789012345678"))
            assertThat(WorkflowStructuredOutput.decode(n, "{\"x\":"+number+"}").get("x")).isInstanceOf(BigDecimal.class);
        bad(n, "{\"x\":[[[[[[1]]]]]]}");
    }
    @Test void schemaMustDeclareAllFieldsExactlyAndRejectUnsupportedKeywords() throws Exception {
        for (String required : List.of("[]", "[\"x\",\"x\"]", "[\"unknown\"]", "null", "[1]")) {
            var n=node("{\"x\":{\"type\":\"string\"}}",required);
            assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        }
        for (String prop : List.of("{\"type\":\"object\"}", "{\"type\":\"integer\"}", "{\"type\":\"string\",\"maxLength\":2001}",
                "{\"type\":\"string\",\"maxLength\":-1}", "{\"type\":\"number\",\"maximum\":10}", "{\"type\":\"boolean\",\"maxLength\":1}",
                "{\"type\":\"array\",\"items\":{\"type\":\"number\"}}", "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":21}",
                "{\"type\":\"array\"}", "null")) {
            var n=node("{\"x\":"+prop+"}","[\"x\"]");
            assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        }
        var n=allTypes();
        ((ObjectNode)n.config().get("outputSchema")).put("additionalProperties",true);
        assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
    }
    @Test void schemaNamesLimitsAndPrimaryOutputCannotConflict() throws Exception {
        for (String name : List.of("result", "a.b", " x", "{{x}}", "x".repeat(65))) {
            var n=node("{\""+name+"\":{\"type\":\"string\"}}", "[\""+name+"\"]");
            assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        }
        var n=node("{\"answer\":{\"type\":\"string\"}}","[\"answer\"]");
        ((ObjectNode)n.config()).put("outputVariable","answer");
        assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        var another=allTypes(); var schema=(ObjectNode)another.config().get("outputSchema");
        schema.put("description","secret");
        var bad=another;
        assertThatThrownBy(()->WorkflowStructuredOutput.validate(bad)).isInstanceOf(BizException.class).hasMessageContaining("schema");
    }
    @Test void legacyNodeDoesNotAcquireSchemaOrPromptInstructions() {
        var n=new WorkflowNodeSpec("llm","LLM","llm",JSON.createObjectNode().put("prompt","p"));
        String before=n.config().toString();
        WorkflowStructuredOutput.validate(n);
        assertThat(WorkflowStructuredOutput.systemPrompt(n,"original")).isEqualTo("original");
        assertThat(WorkflowStructuredOutput.fields(n)).isEmpty();
        assertThat(n.config().toString()).isEqualTo(before);
    }

    @Test void fixedOldLlmPublicationKeepsStampRawBytesAndChecksum() throws Exception {
        String raw;
        try (var stream=getClass().getResourceAsStream("/workflow/legacy-llm-7ddff72.json")) {
            assertThat(stream).isNotNull(); raw=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).stripTrailing();
        }
        String sha="34667cc85f6c10f31e3f3f9a9b61c024b89f66e3d35c04a6f9717f569d1f3f7e";
        assertThat(WorkflowPublishedGraph.checksum(raw)).isEqualTo(sha);
        var version=new com.hify.workflow.domain.WorkflowVersion("old-v","old-w",1,1,raw,sha,java.time.Instant.EPOCH);
        var graph=WorkflowPublishedGraph.read(version,JSON);
        new WorkflowGraphValidator().validate(graph);
        assertThat(graph.nodes().get(1).config().has("outputSchema")).isFalse();
        assertThat(raw).contains("\"externalNodeFormat\":1");
        assertThat(WorkflowPublishedGraph.write(graph,JSON)).isEqualTo(raw);
        assertThat(version.getChecksum()).isEqualTo(sha);
    }
    @Test void publicationBudgetIncludesSchemaInstructionsWithoutChangingPromptTemplates() throws Exception {
        var n=allTypes();
        ((ObjectNode)n.config()).put("systemPrompt","x".repeat(15900));
        assertThatThrownBy(()->WorkflowExternalNodes.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("输入预算");
        ((ObjectNode)n.config()).put("systemPrompt","system").put("prompt","{{start.userMessage}}");
        assertThat(WorkflowExternalNodes.validate(n)).containsExactly("{{start.userMessage}}","system");
        assertThat(WorkflowStructuredOutput.systemPrompt(n,"system")).startsWith("system\nReturn exactly").contains("properties");
    }
    @Test void propertyCountAndLongNamesHaveBoundaryControls() throws Exception {
        var n=allTypes(); var s=(ObjectNode)n.config().get("outputSchema");
        var props=s.putObject("properties");var required=s.putArray("required");
        for(int i=0;i<16;i++){props.putObject("f"+i).put("type","string");required.add("f"+i);}
        WorkflowStructuredOutput.validate(n);
        props.putObject("extra").put("type","string");required.add("extra");
        assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        props.removeAll();required.removeAll();
        assertThatThrownBy(()->WorkflowStructuredOutput.validate(n)).isInstanceOf(BizException.class).hasMessageContaining("schema");
        for(int i=0;i<16;i++) {String key="字".repeat(62)+i; props.putObject(key).put("type","array").put("maxItems",20).putObject("items").put("type","string").put("maxLength",2000);required.add(key);}
        assertThat(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
        WorkflowStructuredOutput.validate(n);
    }
    @Test void canonicalNumbersUsePlainFormatAndContextBundleCannotPartiallyOverwrite() throws Exception {
        var n=node("{\"x\":{\"type\":\"number\"}}","[\"x\"]");
        assertThat(WorkflowStructuredOutput.decode(n,"{\"x\":1e-7}").get("result")).isEqualTo("{\"x\":0.0000001}");
        var ctx=new WorkflowExecutionContext("test");ctx.set("extract","result","old");
        assertThatThrownBy(()->ctx.setAll("extract",WorkflowStructuredOutput.decode(n,"{\"x\":1}")))
                .isInstanceOf(BizException.class).hasMessageContaining("不可覆盖");
        assertThat(ctx.snapshot()).containsEntry("extract.result","old").doesNotContainKey("extract.x");
    }
    @Test void conditionRequiresBooleanSingleFieldAndRejectsArrayTextMembership() throws Exception {
        var n=allTypes();var validator=new WorkflowGraphValidator();
        for(String expression:List.of("{{extract.ok}}", "{{extract.amount}} == '1'", "{{extract.name}} contains 'a'"))
            validator.validate(conditionGraph(n,expression));
        for(String expression:List.of("{{extract.questions}}", "{{extract.questions}} contains 'a'", "{{extract.amount}}", "{{extract.name}}"))
            assertThatThrownBy(()->validator.validate(conditionGraph(n,expression))).isInstanceOf(BizException.class).hasMessageContaining("结构化");
        assertThatThrownBy(()->validator.validate(conditionGraph(n,"{{extract.unknown}} == 'x'")))
                .isInstanceOf(BizException.class).hasMessageContaining("未声明");
    }
    private static com.hify.workflow.api.WorkflowDraftRequest conditionGraph(WorkflowNodeSpec n,String expression) {
        return WorkflowFixtures.draft(List.of(WorkflowFixtures.node("start","START"),n,
                WorkflowFixtures.node("route","CONDITION","expression",expression),
                WorkflowFixtures.node("yes","END","output","yes"),WorkflowFixtures.node("no","END","output","no")),
                WorkflowFixtures.edge("start","extract"),WorkflowFixtures.edge("extract","route"),
                new com.hify.workflow.api.WorkflowEdgeSpec("r-y","route","yes","true",false),
                new com.hify.workflow.api.WorkflowEdgeSpec("r-n","route","no",null,true));
    }
    @Test void arraysCannotDirectlyAggregateButScalarFieldsAndCanonicalTextCan() throws Exception {
        var base=WorkflowAggregationTest.graph(WorkflowAggregationTest.merge("left.result","right.result"));
        var nodes=new ArrayList<>(base.nodes());
        var config=allTypes().config();
        nodes.set(2,new WorkflowNodeSpec("left","LLM","left",config.deepCopy()));
        nodes.set(3,new WorkflowNodeSpec("right","LLM","right",config.deepCopy()));
        var validator=new WorkflowGraphValidator();
        for(String field:List.of("result","name","amount","ok")) {
            nodes.set(4,WorkflowAggregationTest.merge("left."+field,"right."+field));
            validator.validate(WorkflowFixtures.draft(nodes,base.edges().toArray(com.hify.workflow.api.WorkflowEdgeSpec[]::new)));
        }
        nodes.set(4,WorkflowAggregationTest.merge("left.questions","right.questions"));
        assertThatThrownBy(()->validator.validate(WorkflowFixtures.draft(nodes,base.edges().toArray(com.hify.workflow.api.WorkflowEdgeSpec[]::new))))
                .isInstanceOf(BizException.class).hasMessageContaining("不支持结构化数组");
        nodes.set(4,WorkflowAggregationTest.merge("left.result","right.result"));
        nodes.set(5,WorkflowFixtures.node("end","END","output","{{left.name}}"));
        assertThatThrownBy(()->validator.validate(WorkflowFixtures.draft(nodes,base.edges().toArray(com.hify.workflow.api.WorkflowEdgeSpec[]::new))))
                .isInstanceOf(BizException.class).hasMessageContaining("必经上游");
    }
}
