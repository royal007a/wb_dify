package com.hify.workflow.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.workflow.api.*;
import com.hify.provider.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LegacyLlmWriterProbeTest {
 @Test void generate() throws Exception {
  var json=new ObjectMapper();
  var config=json.createObjectNode().put("providerId","fixture").put("modelId","fixture")
    .put("prompt","{{start.userMessage}}").put("temperature",0.2).put("maxOutputTokens",123);
  config.set("modelSnapshot",json.valueToTree(new TextGenerationPort.Profile(
    new ProviderRuntimeConfig("fixture","golden",ProviderType.MOCK,"","","fixture",true),"fixture")));
  var graph=new WorkflowDraftRequest("legacy-llm-golden","7ddff72 old writer",1,List.of(
    new WorkflowNodeSpec("start","START","start",json.createObjectNode()),
    new WorkflowNodeSpec("llm","LLM","llm",config),
    new WorkflowNodeSpec("end","END","end",json.createObjectNode().put("output","{{llm.result}}"))),
    List.of(new WorkflowEdgeSpec("s-l","start","llm",null,false),new WorkflowEdgeSpec("l-e","llm","end",null,false)));
  new WorkflowGraphValidator().validate(graph);
  String raw=WorkflowPublishedGraph.write(graph,json);
  System.out.println("LEGACY_LLM_RAW="+raw);
  System.out.println("LEGACY_LLM_SHA="+WorkflowPublishedGraph.checksum(raw));
 }
}
