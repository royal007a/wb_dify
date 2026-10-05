package com.hify.workflow.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.workflow.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LegacyWriterProbeTest {
 @Test void generate() throws Exception {
  var json=new ObjectMapper();
  var graph=new WorkflowDraftRequest("legacy-golden","7ddff72 old writer",1,List.of(
   new WorkflowNodeSpec("start","START","start",json.readTree("{}")),
   new WorkflowNodeSpec("route","CONDITION","route",json.readTree("{\"expression\":\"true\"}")),
   new WorkflowNodeSpec("left","TEMPLATE","left",json.readTree("{\"template\":\"旧版 {{start.userMessage}}\",\"historicalExtension\":\"retain\"}")),
   new WorkflowNodeSpec("right","TEMPLATE","right",json.readTree("{\"template\":\"other\"}")),
   new WorkflowNodeSpec("leftEnd","END","leftEnd",json.readTree("{\"output\":\"{{left.result}}\"}")),
   new WorkflowNodeSpec("rightEnd","END","rightEnd",json.readTree("{\"output\":\"{{right.result}}\"}"))),
   List.of(new WorkflowEdgeSpec("s-r","start","route",null,false),new WorkflowEdgeSpec("r-l","route","left","true",false),new WorkflowEdgeSpec("r-r","route","right",null,true),new WorkflowEdgeSpec("l-e","left","leftEnd",null,false),new WorkflowEdgeSpec("r-e","right","rightEnd",null,false)));
  new WorkflowGraphValidator().validate(graph);
  String raw=WorkflowPublishedGraph.write(graph,json);
  System.out.println("LEGACY_RAW="+raw);
  System.out.println("LEGACY_SHA="+WorkflowPublishedGraph.checksum(raw));
 }
}
