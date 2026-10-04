package com.hify.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.workflow.api.WorkflowNodeSpec;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hify.workflow.application.WorkflowFixtures.*;

class WorkflowInputsTest {
    private final ObjectMapper json = new ObjectMapper();
    private WorkflowNodeSpec start(String fields) throws Exception {
        return new WorkflowNodeSpec("entry", "START", "开始", json.readTree("{\"inputs\":" + fields + "}"));
    }
    @Test void preservesFalseZeroAndSinglePassText() throws Exception {
        var node=start("""
          [{"name":"flag","type":"boolean","required":false,"default":false},
           {"name":"count","type":"number","required":false,"default":0},
           {"name":"title","type":"text","required":true}]
          """);
        var bound=WorkflowInputs.bind(node,json.readTree("{\"title\":\"{{entry.flag}}\"}"));
        assertThat(bound.get("flag")).isEqualTo(false);
        assertThat(bound.get("count")).isEqualTo(new java.math.BigDecimal("0"));
        var ctx=new WorkflowExecutionContext("entry","input");bound.forEach((k,v)->ctx.set("entry",k,v));
        assertThat(ctx.resolve("{{entry.title}}/{{entry.flag}}/{{entry.count}}")).isEqualTo("{{entry.flag}}/false/0");
        assertThat(WorkflowInputs.variables(node)).containsExactlyInAnyOrder("userMessage","flag","count","title");
    }
    @Test void rejectsInvalidSchemaWithoutCoercion() throws Exception {
        String[] bad={
          "null", "{}", "[null]",
          "[{\"name\":\"userMessage\",\"type\":\"text\",\"required\":true}]",
          "[{\"name\":\"a\",\"type\":\"object\",\"required\":true}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":\"true\"}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":true,\"default\":\"x\"}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":false}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":false,\"default\":null}]",
          "[{\"name\":\"a\",\"type\":\"boolean\",\"required\":false,\"default\":\"false\"}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":true,\"maxLength\":4001}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":true,\"options\":[]}]",
          "[{\"name\":\"a\",\"type\":\"enum\",\"required\":true,\"options\":[\"a\",\"a\"]}]",
          "[{\"name\":\"a\",\"type\":\"text\",\"required\":true,\"typo\":true}]"
        };
        for(String fields:bad){var node=start(fields);assertThatThrownBy(()->WorkflowInputs.schema(node)).as(fields).isInstanceOf(com.hify.common.BizException.class);}
        var duplicate=start("[{\"name\":\"a\",\"type\":\"text\",\"required\":true},{\"name\":\"a\",\"type\":\"text\",\"required\":true}]");
        assertThatThrownBy(()->WorkflowInputs.schema(duplicate)).isInstanceOf(com.hify.common.BizException.class);
    }
    @Test void rejectsMissingUnknownNullNulWrongTypesAndBounds() throws Exception {
        var node=start("""
          [{"name":"text","type":"text","required":true,"maxLength":3},
           {"name":"num","type":"number","required":true},
           {"name":"flag","type":"boolean","required":true},
           {"name":"pick","type":"enum","required":true,"options":["a","b"]}]
          """);
        ObjectNode good=(ObjectNode)json.valueToTree(Map.of("text","abc","num",0,"flag",false,"pick","a"));
        assertThat(WorkflowInputs.bind(node,good)).hasSize(4);
        for(String key:java.util.List.of("text","num","flag","pick")){
            var missing=good.deepCopy();missing.remove(key);
            assertThatThrownBy(()->WorkflowInputs.bind(node,missing)).isInstanceOf(com.hify.common.BizException.class);
            var nullable=good.deepCopy();nullable.putNull(key);
            assertThatThrownBy(()->WorkflowInputs.bind(node,nullable)).isInstanceOf(com.hify.common.BizException.class);
        }
        for(var change:Map.of("text","abcd","num","0","flag","false","pick","other","unknown","x").entrySet()){
            var bad=good.deepCopy().put(change.getKey(),change.getValue());
            assertThatThrownBy(()->WorkflowInputs.bind(node,bad)).isInstanceOf(com.hify.common.BizException.class);
        }
        assertThatThrownBy(()->WorkflowInputs.bind(node,good.deepCopy().put("num",1000000000001L))).isInstanceOf(com.hify.common.BizException.class);
        assertThatThrownBy(()->WorkflowInputs.bind(node,good.deepCopy().put("text","a\u0000b"))).isInstanceOf(com.hify.common.BizException.class);
        assertThatThrownBy(()->WorkflowInputs.bind(node,json.nullNode())).isInstanceOf(com.hify.common.BizException.class);
    }

    @Test void admissionRunsBeforeExternalExecutionWithPositiveControl() throws Exception {
        var app=mock(WorkflowApplicationService.class);
        var runs=mock(com.hify.workflow.infrastructure.WorkflowRunRepository.class);
        var nodes=mock(com.hify.workflow.infrastructure.WorkflowNodeRunRepository.class);
        var knowledge=mock(com.hify.knowledge.api.KnowledgeRetrievalPort.class);
        var external=mock(WorkflowExternalNodes.class);
        var start=start("[{\"name\":\"topic\",\"type\":\"text\",\"required\":true}]");
        var graph=draft(java.util.List.of(start,node("ask","LLM","providerId","mock","modelId","model","prompt","{{entry.topic}}"),node("end","END","output","{{ask.result}}")),edge("entry","ask"),edge("ask","end"));
        String raw=WorkflowPublishedGraph.write(graph,json);
        when(app.requireVersion("v")).thenReturn(new com.hify.workflow.domain.WorkflowVersion("v","w",1,1,raw,WorkflowPublishedGraph.checksum(raw),java.time.Instant.now()));
        when(runs.save(any())).thenAnswer(call->call.getArgument(0));
        when(external.execute(any(),any(),any())).thenReturn("ok");
        var engine=new WorkflowEngine(app,runs,nodes,knowledge,json,new WorkflowGraphValidator(),Runnable::run,java.time.Duration.ofSeconds(5),new com.hify.common.ExecutionLifecycle(),external);
        assertThatThrownBy(()->engine.executeWithInputs("v","hello",json.createObjectNode())).isInstanceOf(com.hify.common.BizException.class);
        verify(external,never()).execute(any(),any(),any());verifyNoInteractions(runs,nodes,knowledge);
        assertThat(engine.executeWithInputs("v","hello",json.createObjectNode().put("topic","test")).status()).isEqualTo("SUCCEEDED");
        verify(external,times(1)).execute(any(),any(),any());verify(runs,times(2)).save(any());verify(nodes,times(6)).save(any());
    }

    @Test void decimalRequestAndSnapshotAreExactAndTemplatesArePlain() throws Exception {
        var node=start("[{\"name\":\"n\",\"type\":\"number\",\"required\":true}]");
        for(String raw:java.util.List.of("1e12","1e-7","1e-400","0.12345678901234567890123","999999999999.9999999","0","0.0","-0.0")) {
            var request=json.readValue("{\"input\":\"x\",\"inputs\":{\"n\":"+raw+"}}",com.hify.workflow.api.WorkflowRunRequest.class);
            var bound=WorkflowInputs.bind(node,request.inputs());
            var ctx=new WorkflowExecutionContext("entry","x");bound.forEach((k,v)->ctx.set("entry",k,v));
            assertThat(ctx.resolve("{{entry.n}}")).as(raw).isEqualTo(new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString());
        }
        for(String raw:java.util.List.of("1000000000000.0000001","-1000000000000.0000001","1e-1001","1e999")) {
            var request=json.readValue("{\"input\":\"x\",\"inputs\":{\"n\":"+raw+"}}",com.hify.workflow.api.WorkflowRunRequest.class);
            assertThatThrownBy(()->WorkflowInputs.bind(node,request.inputs())).as(raw).isInstanceOf(com.hify.common.BizException.class);
        }
        var optional=json.readValue("{\"nodeKey\":\"entry\",\"type\":\"START\",\"name\":\"start\",\"config\":{\"inputs\":[{\"name\":\"n\",\"type\":\"number\",\"required\":false,\"default\":0.12345678901234567890123}]}}",WorkflowNodeSpec.class);
        var graph=draft(java.util.List.of(optional,node("end","END","output","{{entry.n}}")),edge("entry","end"));
        String dsl=WorkflowPublishedGraph.write(graph,json);
        var read=WorkflowPublishedGraph.read(new com.hify.workflow.domain.WorkflowVersion("v","w",1,1,dsl,WorkflowPublishedGraph.checksum(dsl),java.time.Instant.now()),json);
        assertThat(WorkflowInputs.bind(read.nodes().get(0),null).get("n")).isEqualTo(new java.math.BigDecimal("0.12345678901234567890123"));
        var nullable=json.readValue("{\"input\":\"x\",\"inputs\":null}",com.hify.workflow.api.WorkflowRunRequest.class);
        assertThatThrownBy(()->WorkflowInputs.bind(optional,nullable.inputs())).isInstanceOf(com.hify.common.BizException.class);
        var absent=json.readValue("{\"input\":\"x\"}",com.hify.workflow.api.WorkflowRunRequest.class);
        assertThat(absent.inputs()).isNull();
        assertThat(WorkflowInputs.bind(optional,absent.inputs()).get("n")).isEqualTo(new java.math.BigDecimal("0.12345678901234567890123"));
        assertThat(json.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
    }

    @Test void textWhitespaceUtf16AndFieldCountsAreBounded() throws Exception {
        var node=start("[{\"name\":\"s\",\"type\":\"text\",\"required\":true,\"maxLength\":2}]");
        for(String raw:java.util.List.of("", " ","\u00a0","\ufeff","\u3000","\t\n"))
            assertThatThrownBy(()->WorkflowInputs.bind(node,json.createObjectNode().put("s",raw))).isInstanceOf(com.hify.common.BizException.class);
        assertThat(WorkflowInputs.bind(node,json.createObjectNode().put("s","😀")).get("s")).isEqualTo("😀");
        assertThatThrownBy(()->WorkflowInputs.bind(node,json.createObjectNode().put("s","😀x"))).isInstanceOf(com.hify.common.BizException.class);
        assertThat(WorkflowInputs.bind(node,json.createObjectNode().put("s","\u200b")).get("s")).isEqualTo("\u200b");
        var schema=json.createArrayNode();var values=json.createObjectNode();
        for(int i=0;i<16;i++){schema.addObject().put("name","n"+i).put("type","boolean").put("required",true);values.put("n"+i,false);}
        var sixteen=new WorkflowNodeSpec("entry","START","start",json.createObjectNode().set("inputs",schema));
        assertThat(WorkflowInputs.bind(sixteen,values)).hasSize(16);
        assertThatThrownBy(()->WorkflowInputs.bind(sixteen,values.deepCopy().put("extra",false))).isInstanceOf(com.hify.common.BizException.class);
        schema.addObject().put("name","extra").put("type","boolean").put("required",true);
        assertThatThrownBy(()->WorkflowInputs.schema(sixteen)).isInstanceOf(com.hify.common.BizException.class);
    }
}
